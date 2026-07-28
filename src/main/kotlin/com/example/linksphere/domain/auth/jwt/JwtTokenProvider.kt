package com.example.linksphere.domain.auth.jwt

import com.example.linksphere.global.exception.InvalidTokenException
import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.MalformedJwtException
import io.jsonwebtoken.UnsupportedJwtException
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import javax.crypto.SecretKey

enum class TokenType { ACCESS, REFRESH }

private const val TYPE_CLAIM = "typ"

@Component
class JwtTokenProvider(
    // 기본값을 두지 않는다: 설정 누락 시 조용히 넘어가지 않고 기동을 실패시켜 즉시 드러나게 한다.
    @Value("\${jwt.secret}")
    private val secretKey: String,
    @Value("\${jwt.access-token-validity:3600000}")
    private val accessTokenValidity: Long, // 1 hour
    @Value("\${jwt.refresh-token-validity:604800000}")
    private val refreshTokenValidity: Long, // 7 days
) {
    private val logger = org.slf4j.LoggerFactory.getLogger(JwtTokenProvider::class.java)
    private val key: SecretKey = Keys.hmacShaKeyFor(secretKey.toByteArray(StandardCharsets.UTF_8))

    fun createAccessToken(userId: String): String = createToken(userId, accessTokenValidity, TokenType.ACCESS)

    fun createRefreshToken(userId: String): String = createToken(userId, refreshTokenValidity, TokenType.REFRESH)

    private fun createToken(userId: String, validity: Long, type: TokenType): String {
        val now = Date()
        val validityDate = Date(now.time + validity)

        return Jwts.builder()
            .subject(userId)
            .claim(TYPE_CLAIM, type.name)
            .issuedAt(now)
            .expiration(validityDate)
            .signWith(key)
            .compact()
    }

    /**
     * 서명·만료와 함께 토큰 타입(access/refresh)까지 검증한다.
     * refresh 토큰을 Authorization 헤더로, access 토큰을 /auth/refresh 로 보내는 오용을 막기 위함이다.
     */
    fun validateToken(token: String, expectedType: TokenType) {
        val claims =
            try {
                Jwts.parser().verifyWith(key).build().parseSignedClaims(token)
            } catch (e: io.jsonwebtoken.security.SecurityException) {
                logger.error("Invalid JWT signature: {}", e.message)
                throw e
            } catch (e: MalformedJwtException) {
                logger.error("Invalid JWT token: {}", e.message)
                throw e
            } catch (e: ExpiredJwtException) {
                logger.warn("JWT token is expired: {}", e.message)
                throw e
            } catch (e: UnsupportedJwtException) {
                logger.error("JWT token is unsupported: {}", e.message)
                throw e
            } catch (e: IllegalArgumentException) {
                logger.error("JWT claims string is empty: {}", e.message)
                throw e
            }

        val actualType = claims.payload.get(TYPE_CLAIM, String::class.java)
        if (actualType != expectedType.name) {
            throw InvalidTokenException("Expected $expectedType token but got $actualType")
        }
    }

    fun getUserId(token: String): String = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload.subject
}
