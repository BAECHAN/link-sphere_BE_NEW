package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.exception.InvalidCredentialsException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberService: MemberService,
    private val memberSessionService: MemberSessionService,
    private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder,
) {

    @Transactional
    fun signup(request: SignupRequest): AccountResponse = toAccountResponse(
        memberService.signup(
            request.copy(password = passwordEncoder.encode(request.password)),
        ),
    )

    @Transactional
    fun login(request: LoginRequest): AuthResult {
        val member =
            try {
                memberService.findByEmail(request.email)
            } catch (e: IllegalArgumentException) {
                throw InvalidCredentialsException("Invalid email or password")
            }

        if (!passwordEncoder.matches(request.password, member.password)) {
            throw InvalidCredentialsException("Invalid email or password")
        }

        val session = memberSessionService.createSession(member.id!!)
        return AuthResult(session.accessToken, session.refreshToken, session.refreshExpiresInSeconds)
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
