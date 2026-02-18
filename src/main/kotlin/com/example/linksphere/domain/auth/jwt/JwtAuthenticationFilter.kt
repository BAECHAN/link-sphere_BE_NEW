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
class JwtAuthenticationFilter(private val jwtTokenProvider: JwtTokenProvider) :
        OncePerRequestFilter() {

    private val logger = org.slf4j.LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain
    ) {
        val token = resolveToken(request)
        logger.info(
                "JwtAuthenticationFilter: Processing ${request.method} ${request.requestURI}, Token: ${token?.take(10)}..."
        )

        try {
            if (token != null && jwtTokenProvider.validateToken(token)) {
                val userId = jwtTokenProvider.getUserId(token)
                // In a real app, you might load UserDetails here.
                // For now, we create a simple authenticated token with the userId.
                val auth = UsernamePasswordAuthenticationToken(userId, null, emptyList())
                SecurityContextHolder.getContext().authentication = auth
            }
        } catch (e: ExpiredJwtException) {
            logger.error("Expired JWT token", e)
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
        // SSE 연결 시 EventSource는 커스텀 헤더를 지원하지 않으므로
        // 쿼리 파라미터에서 토큰을 읽어옴
        val queryToken = request.getParameter("token")
        if (!queryToken.isNullOrBlank()) {
            logger.info(
                    "JwtAuthenticationFilter: Found token in query param: ${queryToken.take(10)}..."
            )
            return queryToken
        }
        logger.info(
                "JwtAuthenticationFilter: No token found in header or query. URI: ${request.requestURI}"
        )
        return null
    }
}
