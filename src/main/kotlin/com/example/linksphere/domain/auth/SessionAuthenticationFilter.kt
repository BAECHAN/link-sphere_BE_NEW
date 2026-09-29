package com.example.linksphere.domain.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * JwtAuthenticationFilter를 대체한다. 토큰 자체를 검증(서명 확인)하는 대신 DB
 * (MemberSessionService)에 물어봐서 이 access 토큰이 아직 유효한지 판정한다 - 로그아웃이
 * 실제로 즉시 반영되려면 이 조회가 필요하다(docs/plans/2026-09-28-auth-hardening.md 참고).
 *
 * request.setAttribute("exception", ...) 계약은 그대로 유지한다 - CustomAuthenticationEntryPoint가
 * 이 값을 읽어 TOKEN_EXPIRED(재시도로 회복 가능)와 INVALID_TOKEN(즉시 로그아웃)을 구분한다.
 */
@Component
class SessionAuthenticationFilter(private val memberSessionService: MemberSessionService) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)

        if (token != null) {
            when (val result = memberSessionService.checkAccessToken(token)) {
                is AccessTokenCheck.Valid -> {
                    val auth = UsernamePasswordAuthenticationToken(result.memberId.toString(), null, emptyList())
                    // details에 familyId를 실어 SecurityUtils.getSessionFamilyId()로 꺼내 쓴다
                    // (FcmTokenController가 토큰 등록 시 사용) - principal은 getUserId()가
                    // UUID로 파싱하는 계약이라 그대로 memberId 문자열만 담는다.
                    auth.details = result.familyId
                    SecurityContextHolder.getContext().authentication = auth
                }
                AccessTokenCheck.Expired -> request.setAttribute("exception", "TOKEN_EXPIRED")
                AccessTokenCheck.Invalid -> request.setAttribute("exception", "INVALID_TOKEN")
            }
        }

        filterChain.doFilter(request, response)
    }

    /**
     * X-Access-Token을 우선 읽는다 - CloudFront OAC가 오리진 요청에 자신의 SigV4 서명을
     * Authorization 헤더에 덮어쓰기 때문에(Signing behavior: Always sign), 클라이언트의
     * 실제 토큰은 그 헤더를 쓸 수 없다(docs/plans/2026-09-28-auth-hardening.md Phase 7 참고).
     * Authorization Bearer 폴백은 로컬 개발(Function URL을 거치지 않음)과 Swagger UI
     * (SwaggerConfig.kt의 bearerAuth 스킴이 이 형식으로 보냄)를 위해 남겨둔다.
     */
    private fun resolveToken(request: HttpServletRequest): String? {
        val accessTokenHeader = request.getHeader("X-Access-Token")
        if (!accessTokenHeader.isNullOrBlank()) {
            return accessTokenHeader
        }
        val bearerToken = request.getHeader("Authorization")
        if (bearerToken != null && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7)
        }
        return null
    }
}
