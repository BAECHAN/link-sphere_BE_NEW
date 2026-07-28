package com.example.linksphere.domain.auth.jwt

import com.example.linksphere.global.exception.InvalidTokenException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class JwtTokenProviderTest {

    private val jwtTokenProvider = JwtTokenProvider(
        secretKey = "test-secret-key-must-be-long-enough-for-hs256-signing",
        accessTokenValidity = 3600000,
        refreshTokenValidity = 604800000,
    )

    @Test
    fun `validateToken accepts an access token as ACCESS type`() {
        val token = jwtTokenProvider.createAccessToken("user-1")

        assertDoesNotThrow { jwtTokenProvider.validateToken(token, TokenType.ACCESS) }
    }

    @Test
    fun `validateToken rejects a refresh token used as an access token`() {
        val refreshToken = jwtTokenProvider.createRefreshToken("user-1")

        assertThrows(InvalidTokenException::class.java) {
            jwtTokenProvider.validateToken(refreshToken, TokenType.ACCESS)
        }
    }

    @Test
    fun `validateToken accepts a refresh token as REFRESH type`() {
        val token = jwtTokenProvider.createRefreshToken("user-1")

        assertDoesNotThrow { jwtTokenProvider.validateToken(token, TokenType.REFRESH) }
    }

    @Test
    fun `validateToken rejects an access token used as a refresh token`() {
        val accessToken = jwtTokenProvider.createAccessToken("user-1")

        assertThrows(InvalidTokenException::class.java) {
            jwtTokenProvider.validateToken(accessToken, TokenType.REFRESH)
        }
    }
}
