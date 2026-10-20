package com.example.linksphere.domain.post

import com.example.linksphere.domain.auth.AccessTokenCheck
import com.example.linksphere.domain.auth.AccountDeletionService
import com.example.linksphere.domain.auth.AuthController
import com.example.linksphere.domain.auth.AuthResult
import com.example.linksphere.domain.auth.AuthService
import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.auth.PasswordResetService
import com.example.linksphere.domain.comment.CommentController
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.config.SecurityConfig
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 공개 조회(GET /post, /post/{id}, /post/{id}/comment)가 만료·무효 토큰을 비로그인으로 삼키지 않고 401을
 * 돌려주는지 실제 SecurityConfig·SessionAuthenticationFilter·진입점으로 검증한다. 다른 컨트롤러 테스트는
 * 보안 체인을 빼고 돌아 이 규칙을 볼 수 없다(docs/plans/2026-10-20-public-get-expired-token-401.md).
 */
@WebMvcTest(controllers = [PostController::class, CommentController::class, AuthController::class])
@Import(SecurityConfig::class, CustomAuthenticationEntryPoint::class, CustomAccessDeniedHandler::class)
class PostSecurityTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @MockitoBean private lateinit var postService: PostService

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    @MockitoBean private lateinit var commentService: CommentService

    @MockitoBean private lateinit var authService: AuthService

    @MockitoBean private lateinit var accountDeletionService: AccountDeletionService

    @MockitoBean private lateinit var passwordResetService: PasswordResetService

    private val emptyPage = PostPageResponse(emptyList(), page = 0, size = 10, totalElements = 0, totalPages = 0, last = true)

    @Test
    fun `만료 토큰으로 비공개 글 필터를 조회하면 비로그인 빈 목록 대신 401 TOKEN_EXPIRED를 준다`() {
        `when`(memberSessionService.checkAccessToken("expired-token")).thenReturn(AccessTokenCheck.Expired)

        mockMvc.perform(get("/post").param("filter", "isPrivate").header("X-Access-Token", "expired-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))

        verifyNoInteractions(postService)
    }

    @Test
    fun `무효 토큰으로 목록을 조회하면 401 INVALID_TOKEN을 준다`() {
        `when`(memberSessionService.checkAccessToken("unknown-token")).thenReturn(AccessTokenCheck.Invalid)

        mockMvc.perform(get("/post").param("filter", "isPrivate").header("X-Access-Token", "unknown-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))

        verifyNoInteractions(postService)
    }

    @Test
    fun `토큰 없이 조회하면 지금처럼 비로그인으로 목록을 준다`() {
        `when`(postService.getAllPosts(null, null, "isPrivate", null, 0, 10, null, true)).thenReturn(emptyPage)

        mockMvc.perform(get("/post").param("filter", "isPrivate"))
            .andExpect(status().isOk)

        verify(postService).getAllPosts(null, null, "isPrivate", null, 0, 10, null, true)
        verifyNoInteractions(memberSessionService)
    }

    @Test
    fun `유효 토큰으로 조회하면 본인 기준으로 목록을 준다`() {
        val memberId = UUID.randomUUID()
        `when`(memberSessionService.checkAccessToken("valid-token")).thenReturn(AccessTokenCheck.Valid(memberId, UUID.randomUUID()))
        `when`(postService.getAllPosts(null, null, "isPrivate", null, 0, 10, memberId, true)).thenReturn(emptyPage)

        mockMvc.perform(get("/post").param("filter", "isPrivate").header("X-Access-Token", "valid-token"))
            .andExpect(status().isOk)

        verify(postService).getAllPosts(null, null, "isPrivate", null, 0, 10, memberId, true)
    }

    @Test
    fun `만료 토큰으로 상세와 댓글 목록을 조회해도 401 TOKEN_EXPIRED를 준다`() {
        `when`(memberSessionService.checkAccessToken("expired-token")).thenReturn(AccessTokenCheck.Expired)
        val postId = UUID.randomUUID()

        mockMvc.perform(get("/post/$postId").header("X-Access-Token", "expired-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
        mockMvc.perform(get("/post/$postId/comment").header("X-Access-Token", "expired-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))

        verifyNoInteractions(postService, commentService)
    }

    @Test
    fun `refresh는 만료 토큰을 X-Access-Token으로 받아도 막지 않고 컨트롤러까지 넘긴다`() {
        `when`(memberSessionService.checkAccessToken("expired-token")).thenReturn(AccessTokenCheck.Expired)
        `when`(authService.refresh("refresh-token", "expired-token"))
            .thenReturn(AuthResult("new-access-token", "new-refresh-token", refreshExpiresInSeconds = 604800L))

        mockMvc.perform(
            post("/auth/refresh")
                .cookie(Cookie("__Host-refreshToken", "refresh-token"))
                .header("X-Access-Token", "expired-token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))

        verify(authService).refresh("refresh-token", "expired-token")
    }
}
