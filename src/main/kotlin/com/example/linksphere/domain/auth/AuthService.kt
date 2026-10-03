package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidActionTokenException
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.infra.mail.MailService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberService: MemberService,
    private val memberSessionService: MemberSessionService,
    private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
    private val rateLimitService: RateLimitService,
    private val memberActionTokenRepository: MemberActionTokenRepository,
    private val mailService: MailService,
    @Value("\${app.frontend.url:}") private val frontendUrl: String,
) {

    private val logger = LoggerFactory.getLogger(AuthService::class.java)

    companion object {
        // 이메일 축은 "특정 계정을 노려 비밀번호를 무작위 대입"을 막는다 - 계정당 15분에 5회면
        // 정상 사용자가 비밀번호를 몇 번 틀리는 정도는 여유 있게 통과한다.
        private val LOGIN_FAIL_EMAIL_WINDOW = Duration.ofMinutes(15)
        private const val LOGIN_FAIL_EMAIL_LIMIT = 5

        // IP 축은 "여러 계정을 순회하며 시도"를 막는다 - 사무실·카페 공유 IP(NAT)에서 여러
        // 사용자가 동시에 로그인 실패할 수 있어 이메일 축보다 넉넉하게 잡는다.
        private val LOGIN_FAIL_IP_WINDOW = Duration.ofMinutes(15)
        private const val LOGIN_FAIL_IP_LIMIT = 20

        // 대량 가입(봇)을 막는다 - 이메일 인증 전이라 이메일 축은 의미가 없어(매번 새 이메일)
        // IP만 본다.
        private val SIGNUP_IP_WINDOW = Duration.ofHours(1)
        private const val SIGNUP_IP_LIMIT = 5

        private val EMAIL_VERIFY_TOKEN_VALIDITY = Duration.ofHours(24)
        private val EMAIL_VERIFY_EMAIL_WINDOW = Duration.ofHours(1)
        private const val EMAIL_VERIFY_EMAIL_LIMIT = 3
        private val EMAIL_VERIFY_IP_WINDOW = Duration.ofHours(1)
        private const val EMAIL_VERIFY_IP_LIMIT = 10
    }

    @Transactional
    fun signup(request: SignupRequest, clientIp: String?): AccountResponse {
        val ipBucket = clientIp?.let { "signup:ip:$it" }
        rateLimitService.consume(ipBucket, SIGNUP_IP_LIMIT, SIGNUP_IP_WINDOW)

        val member =
            memberService.signup(
                request.copy(password = passwordEncoder.encode(request.password)),
            )

        // 발송(토큰 생성 포함)이 실패해도 가입 자체는 성공해야 한다 - MailService 자신도
        // 내부에서 예외를 삼키지만, 토큰 저장 등 그 앞단이 실패하는 경우까지 방어한다.
        try {
            sendVerificationEmail(member)
        } catch (e: Exception) {
            logger.error("[AuthService] 가입 인증메일 준비 실패(memberId=${member.id}): ${e.message}")
        }

        return toAccountResponse(member)
    }

    // 공개 API - 로그인 여부와 무관하게 이메일만으로 재발송 가능하다. 존재하지 않는 이메일·
    // 이미 인증된 이메일이어도 항상 같은 방식으로 조용히 끝난다(컨트롤러가 항상 200).
    @Transactional
    fun requestEmailVerification(email: String, clientIp: String?) {
        val normalizedEmail = email.trim().lowercase()
        val emailBucket = "email-verify:${SecureToken.hash(normalizedEmail)}"
        val ipBucket = clientIp?.let { "email-verify:ip:$it" }
        rateLimitService.consume(emailBucket, EMAIL_VERIFY_EMAIL_LIMIT, EMAIL_VERIFY_EMAIL_WINDOW)
        rateLimitService.consume(ipBucket, EMAIL_VERIFY_IP_LIMIT, EMAIL_VERIFY_IP_WINDOW)

        val member = memberService.findByEmailOrNull(normalizedEmail) ?: return
        if (member.emailVerified) return

        sendVerificationEmail(member)
    }

    @Transactional
    fun confirmEmailVerification(rawToken: String) {
        val token =
            memberActionTokenRepository.findByTokenHash(SecureToken.hash(rawToken))
                ?.takeIf { it.purpose == MemberActionTokenPurpose.EMAIL_VERIFY }
                ?: throw InvalidActionTokenException("Invalid or expired token")

        val consumed = memberActionTokenRepository.consumeIfActive(token.id, Instant.now())
        if (consumed == 0) {
            throw InvalidActionTokenException("Invalid or expired token")
        }

        memberService.markEmailVerified(token.memberId)
    }

    private fun sendVerificationEmail(member: TableMember) {
        val raw = SecureToken.generate()
        memberActionTokenRepository.save(
            TableMemberActionToken(
                memberId = member.id!!,
                purpose = MemberActionTokenPurpose.EMAIL_VERIFY,
                tokenHash = SecureToken.hash(raw),
                expiresAt = Instant.now().plus(EMAIL_VERIFY_TOKEN_VALIDITY),
            ),
        )

        val link = "$frontendUrl/verify-email?token=$raw"
        mailService.send(
            member.email,
            "이메일 인증 안내",
            "<p>아래 링크를 눌러 이메일 인증을 완료해주세요. 24시간 동안 유효합니다.</p><p><a href=\"$link\">$link</a></p>",
        )
    }

    @Transactional
    fun login(request: LoginRequest, clientIp: String?): AuthResult {
        val emailBucket = "login-fail:${SecureToken.hash(request.email.trim().lowercase())}"
        val ipBucket = clientIp?.let { "login-fail:ip:$it" }
        rateLimitService.checkNotExceeded(emailBucket, LOGIN_FAIL_EMAIL_LIMIT, LOGIN_FAIL_EMAIL_WINDOW)
        rateLimitService.checkNotExceeded(ipBucket, LOGIN_FAIL_IP_LIMIT, LOGIN_FAIL_IP_WINDOW)

        val member = memberService.findByEmailOrNull(request.email)
        if (member == null) {
            recordLoginFailure(emailBucket, ipBucket)
            throw InvalidCredentialsException("Invalid email or password")
        }

        if (!passwordEncoder.matches(request.password, member.password)) {
            recordLoginFailure(emailBucket, ipBucket)
            throw InvalidCredentialsException("Invalid email or password")
        }

        // 비밀번호가 일치한 뒤에만 유예 상태를 확인한다 - 먼저 확인하면 "이 이메일이 탈퇴
        // 유예 중"이라는 사실이 비밀번호 없이도 외부에 드러난다. cancelPendingDeletion은
        // 유예 중이 아니었으면 그냥 0행 업데이트로 끝나 false를 반환한다(정상 로그인).
        // false인데 member.deletionRequestedAt이 채워져 있다면 AccountPurgeService가 먼저
        // 그 회원을 가져간 것이다(경합 R1) - 실패 기록 없이 로그인 실패로 처리한다(퍼지된
        // 회원의 비밀번호는 어차피 매칭 불가능한 값으로 바뀌어 있어 이 분기가 아니어도
        // 결국 위의 matches()에서 걸린다, 여기 도달하는 건 매칭 UPDATE와 퍼지 UPDATE
        // 사이의 아주 좁은 창일 때뿐이다).
        val deletionCancelled = memberService.cancelPendingDeletion(member.id!!)
        if (!deletionCancelled && member.deletionRequestedAt != null) {
            throw InvalidCredentialsException("Invalid email or password")
        }

        val session = memberSessionService.createSession(member.id!!)
        return AuthResult(session.accessToken, session.refreshToken, session.refreshExpiresInSeconds, deletionCancelled)
    }

    private fun recordLoginFailure(emailBucket: String, ipBucket: String?) {
        rateLimitService.recordHit(emailBucket, LOGIN_FAIL_EMAIL_WINDOW)
        rateLimitService.recordHit(ipBucket, LOGIN_FAIL_IP_WINDOW)
    }

    // 회전(재사용 탐지 포함)은 MemberSessionService.rotate가 전담한다 - 실패 시
    // InvalidTokenException을 직접 던지므로 여기서 다시 감쌀 필요가 없다.
    @Transactional
    fun refresh(refreshToken: String): AuthResult {
        val session = memberSessionService.rotate(refreshToken)
        return AuthResult(session.accessToken, session.refreshToken, session.refreshExpiresInSeconds)
    }

    // refreshToken이 없거나(쿠키 미전송) 이미 무효해도 조용히 넘어간다 - 로그아웃은
    // "이 세션이 더 이상 못 쓰이게" 하는 게 목적이지, 세션이 이미 없다고 에러를 낼 이유가 없다.
    @Transactional
    fun logout(refreshToken: String?) {
        memberSessionService.revokeByRefreshToken(refreshToken)
    }

    @Transactional
    fun logoutAll(userId: String) {
        memberSessionService.revokeAllForMember(UUID.fromString(userId))
    }

    // 성공하면 모든 세션(이 기기 포함)을 폐기한 뒤 이 기기에만 새 세션을 발급한다 - 다른
    // 기기는 재로그인이 필요해진다(비밀번호가 유출됐을 가능성에 대비한 의도된 동작).
    @Transactional
    fun changePassword(userId: String, request: ChangePasswordRequest): AuthResult {
        val id = UUID.fromString(userId)
        val member = memberService.findById(id)

        if (!passwordEncoder.matches(request.currentPassword, member.password)) {
            throw InvalidCredentialsException("Current password is incorrect")
        }

        memberService.changePassword(id, passwordEncoder.encode(request.newPassword))
        memberSessionService.revokeAllForMember(id)
        val session = memberSessionService.createSession(id)
        return AuthResult(session.accessToken, session.refreshToken, session.refreshExpiresInSeconds)
    }

    fun getAccount(userId: String): AccountResponse = toAccountResponse(memberService.findById(UUID.fromString(userId)))

    @Transactional
    fun updateAccount(userId: String, request: UpdateAccountRequest): AccountResponse = toAccountResponse(memberService.updateAccount(UUID.fromString(userId), request))

    // userId가 없으면(가입 화면, 비로그인 조회) 본인 제외 없이 순수 존재 여부만 확인한다
    fun isNicknameAvailable(userId: String?, nickname: String): NicknameAvailabilityResponse = NicknameAvailabilityResponse(
        memberService.isNicknameAvailable(userId?.let { UUID.fromString(it) }, nickname),
    )

    fun isEmailAvailable(email: String): EmailAvailabilityResponse = EmailAvailabilityResponse(memberService.isEmailAvailable(email))

    private fun toAccountResponse(member: TableMember): AccountResponse = AccountResponse(
        id = member.id.toString(),
        nickname = member.nickname,
        image = member.image,
        emailVerified = member.emailVerified,
        email = member.email,
    )
}
