package com.example.linksphere.domain.auth

import com.example.linksphere.domain.auth.jwt.JwtTokenProvider
import com.example.linksphere.domain.member.MemberService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuthService(
        private val memberService: MemberService,
        private val jwtTokenProvider: JwtTokenProvider,
        private val passwordEncoder: org.springframework.security.crypto.password.PasswordEncoder
) {

    @Transactional
    fun signup(request: SignupRequest): AuthResult {
        // Password encryption is handled in MemberService or here.
        // Let's delegate encryption to MemberService or handle it here before passing.
        // Actually MemberService code showed "TODO: Password encryption needed".
        // Use MemberService for creation, but we need to ensure it uses encryption.
        // Let's modify MemberService to take encrypted password, or encrypt here.
        // Better: Encrypt here and pass to MemberService (modifying request? no, MemberService
        // should probably handle it or take a DTO).
        // Given MemberService.signup takes SignupRequest, let's modify MemberService.

        val member =
                memberService.signup(
                        request.copy(password = passwordEncoder.encode(request.password))
                )
        val accessToken = jwtTokenProvider.createAccessToken(member.id.toString())
        val refreshToken = jwtTokenProvider.createRefreshToken(member.id.toString())

        return AuthResult(accessToken, refreshToken)
    }

    fun login(request: LoginRequest): AuthResult {
        val member = memberService.findByEmail(request.email)
        println("AuthService: Found member ${member.email}, Stored Password: ${member.password}")

        if (!passwordEncoder.matches(request.password, member.password)) {
            println(
                    "AuthService: Password mismatch. Input: ${request.password}, Stored: ${member.password}"
            )
            throw IllegalArgumentException("Invalid password")
        }

        val accessToken = jwtTokenProvider.createAccessToken(member.id.toString())
        val refreshToken = jwtTokenProvider.createRefreshToken(member.id.toString())

        return AuthResult(accessToken, refreshToken)
    }

    fun refresh(refreshToken: String): AuthResult {
        if (!jwtTokenProvider.validateToken(refreshToken)) {
            throw IllegalArgumentException("Invalid refresh token")
        }

        val userId = jwtTokenProvider.getUserId(refreshToken)
        val newAccessToken = jwtTokenProvider.createAccessToken(userId)

        // We can choose to rotate refresh token here or keep the old one.
        // For simplicity and to match the requirement of "update access token", we keep the old one
        // unless it's close to expiry, but for now just returning the new access token.
        // If we want to return the same refresh token to keep the client consistent (or maybe null
        // if not updated).
        // Let's return the old refresh token so the controller can re-set it if needed, or just
        // standard TokenResponse.

        return AuthResult(newAccessToken, refreshToken)
    }
}
