package com.example.linksphere.domain.auth.jwt

import io.jsonwebtoken.ExpiredJwtException
import io.jsonwebtoken.JwtException
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(private val jwtTokenProvider: JwtTokenProvider) : OncePerRequestFilter() {

    private val logger = org.slf4j.LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)

        try {
            if (token != null) {
                jwtTokenProvider.validateToken(token, TokenType.ACCESS)
                val userId = jwtTokenProvider.getUserId(token)
                val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        } catch (e: ExpiredJwtException) {
            // 토큰 만료는 정상 흐름(FE가 refresh로 복구)이므로 스택트레이스를 남기지 않는다
            logger.warn("Expired JWT token")
            request.setAttribute("exception", "TOKEN_EXPIRED")
        } catch (e: JwtException) {
            logger.error("Invalid JWT token", e)
            request.setAttribute("exception", "INVALID_TOKEN")
        } catch (e: Exception) {
            logger.error("Could not set user authentication in security context", e)
            request.setAttribute("exception", "INVALID_TOKEN")
        }

        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearerToken = request.getHeader("Authorization")
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7)
        }
        return null
    }
}
