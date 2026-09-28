package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID

@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberService: MemberService,
    private val memberSessionService: MemberSessionService,
    private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
    private val rateLimitService: RateLimitService,
) {

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
    }

    @Transactional
    fun signup(request: SignupRequest, clientIp: String?): AccountResponse {
        val ipBucket = clientIp?.let { "signup:ip:$it" }
        rateLimitService.checkNotExceeded(ipBucket, SIGNUP_IP_LIMIT, SIGNUP_IP_WINDOW)
        rateLimitService.recordHit(ipBucket, SIGNUP_IP_WINDOW)

        return toAccountResponse(
            memberService.signup(
                request.copy(password = passwordEncoder.encode(request.password)),
            ),
        )
    }

    @Transactional
    fun login(request: LoginRequest, clientIp: String?): AuthResult {
        val emailBucket = "login-fail:${SecureToken.hash(request.email.trim().lowercase())}"
        val ipBucket = clientIp?.let { "login-fail:ip:$it" }
        rateLimitService.checkNotExceeded(emailBucket, LOGIN_FAIL_EMAIL_LIMIT, LOGIN_FAIL_EMAIL_WINDOW)
        rateLimitService.checkNotExceeded(ipBucket, LOGIN_FAIL_IP_LIMIT, LOGIN_FAIL_IP_WINDOW)

        val member =
            try {
                memberService.findByEmail(request.email)
            } catch (e: IllegalArgumentException) {
                recordLoginFailure(emailBucket, ipBucket)
                throw InvalidCredentialsException("Invalid email or password")
            }

        if (!passwordEncoder.matches(request.password, member.password)) {
            recordLoginFailure(emailBucket, ipBucket)
            throw InvalidCredentialsException("Invalid email or password")
        }

        val session = memberSessionService.createSession(member.id!!)
        return AuthResult(session.accessToken, session.refreshToken, session.refreshExpiresInSeconds)
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
    )
}
