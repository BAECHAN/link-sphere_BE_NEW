package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidActionTokenException
import com.example.linksphere.infra.mail.MailService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 비밀번호 찾기 - 요청/확인 두 단계. AccountDeletionService와 같은 이유로 MemberService를
 * 거치지 않고 MemberRepository를 직접 쓴다(이 회원탈퇴·비밀번호찾기 계열 서비스는 계정
 * 자체를 다루는 게 본업이라 도메인 규칙보다 낮은 레벨에서 동작한다).
 */
@Service
class PasswordResetService(
    private val memberRepository: MemberRepository,
    private val memberActionTokenRepository: MemberActionTokenRepository,
    private val memberSessionService: MemberSessionService,
    private val passwordEncoder: PasswordEncoder,
    private val mailService: MailService,
    private val rateLimitService: RateLimitService,
    @Value("\${app.frontend.url:}") private val frontendUrl: String,
) {

    companion object {
        private val TOKEN_VALIDITY = Duration.ofHours(1)

        // 한 이메일함을 반복 스팸하는 것을 막는 축과, 한 IP가 여러 이메일을 훑는 것을
        // 막는 축 - AuthService의 signup/login 레이트리밋과 같은 이중 구조.
        private val REQUEST_EMAIL_WINDOW = Duration.ofHours(1)
        private const val REQUEST_EMAIL_LIMIT = 3
        private val REQUEST_IP_WINDOW = Duration.ofHours(1)
        private const val REQUEST_IP_LIMIT = 10
    }

    // 이메일이 존재하지 않아도 항상 같은 방식으로 조용히 끝난다(컨트롤러가 항상 200) -
    // 존재 여부를 응답 차이로 노출하지 않는다.
    @Transactional
    fun requestReset(email: String, clientIp: String?) {
        val normalizedEmail = email.trim().lowercase()
        val emailBucket = "password-reset:${SecureToken.hash(normalizedEmail)}"
        val ipBucket = clientIp?.let { "password-reset:ip:$it" }
        rateLimitService.checkNotExceeded(emailBucket, REQUEST_EMAIL_LIMIT, REQUEST_EMAIL_WINDOW)
        rateLimitService.checkNotExceeded(ipBucket, REQUEST_IP_LIMIT, REQUEST_IP_WINDOW)
        rateLimitService.recordHit(emailBucket, REQUEST_EMAIL_WINDOW)
        rateLimitService.recordHit(ipBucket, REQUEST_IP_WINDOW)

        val member = memberRepository.findByEmail(normalizedEmail) ?: return

        val raw = SecureToken.generate()
        memberActionTokenRepository.save(
            TableMemberActionToken(
                memberId = member.id!!,
                purpose = MemberActionTokenPurpose.PASSWORD_RESET,
                tokenHash = SecureToken.hash(raw),
                expiresAt = Instant.now().plus(TOKEN_VALIDITY),
            ),
        )

        val link = "$frontendUrl/reset-password?token=$raw"
        mailService.send(
            member.email,
            "비밀번호 재설정 안내",
            "<p>아래 링크에서 새 비밀번호를 설정해주세요. 1시간 동안 유효합니다.</p><p><a href=\"$link\">$link</a></p>",
        )
    }

    @Transactional
    fun confirmReset(rawToken: String, newPassword: String) {
        val token =
            memberActionTokenRepository.findByTokenHash(SecureToken.hash(rawToken))
                ?.takeIf { it.purpose == MemberActionTokenPurpose.PASSWORD_RESET }
                ?: throw InvalidActionTokenException("Invalid or expired token")

        val consumed = memberActionTokenRepository.consumeIfActive(token.id, Instant.now())
        if (consumed == 0) {
            throw InvalidActionTokenException("Invalid or expired token")
        }

        val member =
            memberRepository.findById(token.memberId)
                .orElseThrow { IllegalStateException("Member not found for action token: ${token.memberId}") }
        member.password = passwordEncoder.encode(newPassword)
        memberRepository.save(member)

        memberSessionService.revokeAllForMember(token.memberId)
    }
}
