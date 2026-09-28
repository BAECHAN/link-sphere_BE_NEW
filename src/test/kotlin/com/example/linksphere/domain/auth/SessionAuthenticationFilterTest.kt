package com.example.linksphere.domain.auth

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder

/**
 * X-Access-Token 우선 읽기(CloudFront OAC 전환, docs/plans/2026-09-28-auth-hardening.md
 * Phase 7)를 검증한다. resolveToken은 private, doFilterInternal은 protected라
 * OncePerRequestFilter의 public doFilter를 통해 간접 검증한다.
 */
@ExtendWith(MockitoExtension::class)
class SessionAuthenticationFilterTest {

    @Mock private lateinit var memberSessionService: MemberSessionService

    @InjectMocks private lateinit var filter: SessionAuthenticationFilter

    private val filterChain: FilterChain = FilterChain { _, _ -> }

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `X-Access-Token과 Authorization이 둘 다 있으면 X-Access-Token 값으로 조회한다`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Access-Token", "token-from-custom-header")
        request.addHeader("Authorization", "Bearer token-from-authorization-header")
        `when`(memberSessionService.checkAccessToken("token-from-custom-header"))
            .thenReturn(AccessTokenCheck.Valid(java.util.UUID.randomUUID()))

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        verify(memberSessionService).checkAccessToken("token-from-custom-header")
    }

    @Test
    fun `X-Access-Token이 없으면 Authorization Bearer로 폴백한다`() {
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "Bearer token-from-authorization-header")
        `when`(memberSessionService.checkAccessToken("token-from-authorization-header"))
            .thenReturn(AccessTokenCheck.Valid(java.util.UUID.randomUUID()))

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        verify(memberSessionService).checkAccessToken("token-from-authorization-header")
    }

    @Test
    fun `X-Access-Token이 빈 문자열이면 무시하고 Authorization으로 폴백한다`() {
        val request = MockHttpServletRequest()
        request.addHeader("X-Access-Token", "")
        request.addHeader("Authorization", "Bearer token-from-authorization-header")
        `when`(memberSessionService.checkAccessToken("token-from-authorization-header"))
            .thenReturn(AccessTokenCheck.Valid(java.util.UUID.randomUUID()))

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        verify(memberSessionService).checkAccessToken("token-from-authorization-header")
    }

    @Test
    fun `둘 다 없으면 세션 조회를 하지 않고 인증 없이 통과시킨다`() {
        val request = MockHttpServletRequest()

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        verifyNoInteractions(memberSessionService)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `Authorization에 Bearer 접두어가 없으면(SigV4 서명 등) 무시한다`() {
        // CloudFront OAC가 Authorization을 자신의 SigV4 서명(AWS4-HMAC-SHA256 ...)으로
        // 덮어쓴 경우를 흉내낸다 - Bearer로 시작하지 않으므로 토큰으로 취급하면 안 된다.
        val request = MockHttpServletRequest()
        request.addHeader("Authorization", "AWS4-HMAC-SHA256 Credential=...")

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        verifyNoInteractions(memberSessionService)
        assertNull(SecurityContextHolder.getContext().authentication)
    }

    @Test
    fun `유효한 토큰이면 SecurityContext에 memberId로 인증을 채운다`() {
        val memberId = java.util.UUID.randomUUID()
        val request = MockHttpServletRequest()
        request.addHeader("X-Access-Token", "valid-token")
        `when`(memberSessionService.checkAccessToken("valid-token"))
            .thenReturn(AccessTokenCheck.Valid(memberId))

        filter.doFilter(request, MockHttpServletResponse(), filterChain)

        assertEquals(memberId.toString(), SecurityContextHolder.getContext().authentication.principal)
    }
}
