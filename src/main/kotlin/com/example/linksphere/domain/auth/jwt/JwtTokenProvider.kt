package com.example.linksphere.domain.auth.jwt

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class JwtTokenProvider(
        @Value("\${jwt.secret:defaultSecretKeyMustBeLongEnoughToSecureTheToken1234567890}")
        private val secretKey: String,
        @Value("\${jwt.access-token-validity:900000}")
        private val accessTokenValidity: Long, // 15 mins
        @Value("\${jwt.refresh-token-validity:604800000}")
        private val refreshTokenValidity: Long // 7 days
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(JwtTokenProvider::class.java)
    private val key: SecretKey = Keys.hmacShaKeyFor(secretKey.toByteArray(StandardCharsets.UTF_8))

    fun createAccessToken(userId: String): String {
        return createToken(userId, accessTokenValidity)
    }

    fun createRefreshToken(userId: String): String {
        return createToken(userId, refreshTokenValidity)
    }

    private fun createToken(userId: String, validity: Long): String {
        val now = Date()
        val validityDate = Date(now.time + validity)

        return Jwts.builder()
                .subject(userId)
                .issuedAt(now)
                .expiration(validityDate)
                .signWith(key)
                .compact()
    }

    fun validateToken(token: String): Boolean {
        try {
            Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            return true
        } catch (e: Exception) {
            logger.error("JWT Validation failed", e)
            return false
        }
    }

    fun getUserId(token: String): String {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject
    }
}
