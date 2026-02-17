package com.example.linksphere.domain.auth

import com.example.linksphere.domain.auth.jwt.JwtTokenProvider
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.exception.InvalidCredentialsException
import java.time.format.DateTimeFormatter
import java.util.UUID
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
    fun signup(request: SignupRequest): AccountResponse {
        val member =
                memberService.signup(
                        request.copy(password = passwordEncoder.encode(request.password))
                )

        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        return AccountResponse(
                id = member.id.toString(),
                email = member.email,
                name = member.name,
                image = member.image,
                createdAt = member.createdAt?.format(formatter) ?: "",
                updatedAt = member.updatedAt?.format(formatter) ?: ""
        )
    }

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

    fun getAccount(userId: String): AccountResponse {
        val member = memberService.findById(UUID.fromString(userId))
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME
        return AccountResponse(
                id = member.id.toString(),
                email = member.email,
                name = member.name,
                image = member.image,
                createdAt = member.createdAt?.format(formatter) ?: "",
                updatedAt = member.updatedAt?.format(formatter) ?: ""
        )
    }
}
