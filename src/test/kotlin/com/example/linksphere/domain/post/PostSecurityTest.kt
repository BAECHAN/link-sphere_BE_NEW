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
import com.example.linksphere.domain.member.MemberRole
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.config.SecurityConfig
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import com.example.linksphere.global.exception.DuplicatePostException
import com.example.linksphere.global.exception.InvalidInputException
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.util.UUID

/**
 * 공개 조회(GET /post, /post/{id}, /post/{id}/comment)와 조회 기록(POST /post/{id}/view)이 만료·무효 토큰을 비로그인으로
 * 삼키지 않고 401을 돌려주는지 실제 SecurityConfig·SessionAuthenticationFilter·진입점으로 검증한다. 다른 컨트롤러 테스트는
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

    private val emptyPage = PostPageResponse(emptyList())

    private val postBody = """{"url":"https://example.com"}"""

    private fun givenValidToken(): UUID {
        val memberId = UUID.randomUUID()
        `when`(memberSessionService.checkAccessToken("valid-token")).thenReturn(AccessTokenCheck.Valid(memberId, UUID.randomUUID(), MemberRole.USER))
        return memberId
    }

    private fun postResponse(memberId: UUID) = PostResponse(
        id = UUID.randomUUID(),
        url = "https://example.com",
        title = "제목",
        description = null,
        tags = null,
        categories = emptyList(),
        ogImage = null,
        aiSummary = null,
        createdAt = null,
        aiStatus = AiStatus.NONE,
        isPrivate = false,
        stats = PostStats(viewCount = 0, likeCount = 0, commentCount = 0, bookmarkCount = 0),
        userInteractions = PostUserInteractions(isLiked = false, isBookmarked = false),
        author = UserSummary(memberId, null, null),
    )

    @Test
    fun `같은 키의 글이 이미 있으면 한도를 쓰지 않고 그 글을 201로 돌려준다`() {
        val memberId = givenValidToken()
        val key = UUID.randomUUID()
        val existing = postResponse(memberId)
        `when`(postService.findByIdempotencyKey(memberId, key)).thenReturn(existing)

        mockMvc.perform(
            post("/post")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(postBody),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(existing.id.toString()))

        verifyNoInteractions(rateLimitService)
    }

    @Test
    fun `다른 키로 같은 URL을 1시간 안에 등록하면 한도를 1회 쓰고 409 DUPLICATE_POST를 준다`() {
        val memberId = givenValidToken()
        val key = UUID.randomUUID()
        val request = PostCreateRequest(url = "https://example.com")
        `when`(postService.createPost(memberId, request, null, key)).thenThrow(DuplicatePostException(request.url))

        mockMvc.perform(
            post("/post")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(postBody),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_POST"))

        verify(rateLimitService).consume("post-create:member:$memberId", 20, Duration.ofHours(1))
    }

    @Test
    fun `키 없이 글을 등록하면 한도를 쓰지 않고 400 INVALID_INPUT을 준다`() {
        givenValidToken()

        mockMvc.perform(
            post("/post").header("X-Access-Token", "valid-token").contentType(MediaType.APPLICATION_JSON).content(postBody),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))

        verifyNoInteractions(postService, rateLimitService)
    }

    @Test
    fun `키가 UUID가 아니면 400을 준다`() {
        givenValidToken()

        mockMvc.perform(
            post("/post")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(postBody),
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(postService, rateLimitService)
    }

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
        `when`(postService.getPostsByCursor(isNull(), isNull(), eq("isPrivate"), isNull(), isNull(), eq(10), isNull(), anyEmbeddingGate()))
            .thenReturn(emptyPage)

        mockMvc.perform(get("/post").param("filter", "isPrivate"))
            .andExpect(status().isOk)

        verify(postService).getPostsByCursor(isNull(), isNull(), eq("isPrivate"), isNull(), isNull(), eq(10), isNull(), anyEmbeddingGate())
        verifyNoInteractions(memberSessionService)
    }

    @Test
    fun `유효 토큰으로 조회하면 본인 기준으로 목록을 준다`() {
        val memberId = UUID.randomUUID()
        `when`(memberSessionService.checkAccessToken("valid-token")).thenReturn(AccessTokenCheck.Valid(memberId, UUID.randomUUID(), MemberRole.USER))
        `when`(postService.getPostsByCursor(isNull(), isNull(), eq("isPrivate"), isNull(), isNull(), eq(10), eq(memberId), anyEmbeddingGate()))
            .thenReturn(emptyPage)

        mockMvc.perform(get("/post").param("filter", "isPrivate").header("X-Access-Token", "valid-token"))
            .andExpect(status().isOk)

        verify(postService).getPostsByCursor(isNull(), isNull(), eq("isPrivate"), isNull(), isNull(), eq(10), eq(memberId), anyEmbeddingGate())
    }

    // any()의 실제 반환값(null)을 Kotlin 비-null 인자에 그대로 넘기면 NPE가 난다 - PostServiceTest.anyUuid와 같은 우회
    private fun anyEmbeddingGate(): () -> Boolean {
        ArgumentMatchers.any<() -> Boolean>()
        return { true }
    }

    @Test
    fun `호출 한도는 서비스가 임베딩을 부를 때만 쓴다`() {
        `when`(postService.getPostsByCursor(isNull(), eq("spring"), isNull(), isNull(), eq("next"), eq(5), isNull(), anyEmbeddingGate()))
            .thenAnswer { invocation ->
                assertEquals(0, mockingDetails(rateLimitService).invocations.size)
                invocation.getArgument<() -> Boolean>(7).invoke()
                PostPageResponse(emptyList(), nextCursor = "after-next")
            }

        mockMvc.perform(get("/post").param("search", "spring").param("cursor", "next").param("size", "5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nextCursor").value("after-next"))

        assertEquals(1, mockingDetails(rateLimitService).invocations.size)
    }

    @Test
    fun `잘못된 cursor는 400 INVALID_INPUT을 준다`() {
        `when`(postService.getPostsByCursor(isNull(), isNull(), isNull(), isNull(), eq("broken"), eq(10), isNull(), anyEmbeddingGate()))
            .thenThrow(InvalidInputException("Invalid cursor"))

        mockMvc.perform(get("/post").param("cursor", "broken"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
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
    fun `토큰 없이 조회를 기록하면 비로그인으로 기록하고 판단 뒤 조회수를 돌려준다`() {
        val postId = UUID.randomUUID()
        `when`(postService.recordView(postId, null, null)).thenReturn(PostViewRecordResponse(viewCount = 42))

        mockMvc.perform(post("/post/$postId/view"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.viewCount").value(42))

        verify(postService).recordView(postId, null, null)
    }

    @Test
    fun `유효 토큰으로 조회를 기록하면 본인으로 기록한다`() {
        val userId = UUID.randomUUID()
        `when`(memberSessionService.checkAccessToken("valid-token")).thenReturn(AccessTokenCheck.Valid(userId, UUID.randomUUID(), MemberRole.DEMO))
        val postId = UUID.randomUUID()
        `when`(postService.recordView(postId, userId, null)).thenReturn(PostViewRecordResponse(viewCount = 42))

        mockMvc.perform(post("/post/$postId/view").header("X-Access-Token", "valid-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.viewCount").value(42))

        verify(postService).recordView(postId, userId, null)
    }

    // 비로그인 조회자는 IP로 가린다 - CloudFront가 붙인 "ip:port"에서 IP만 서비스로 넘어가야 한다
    @Test
    fun `조회 기록은 CloudFront가 넘긴 요청자 IP를 서비스에 넘긴다`() {
        val postId = UUID.randomUUID()
        `when`(postService.recordView(postId, null, "203.0.113.7")).thenReturn(PostViewRecordResponse(viewCount = 42))

        mockMvc.perform(post("/post/$postId/view").header("CloudFront-Viewer-Address", "203.0.113.7:51234"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.viewCount").value(42))

        verify(postService).recordView(postId, null, "203.0.113.7")
    }

    @Test
    fun `만료 토큰으로 조회를 기록하면 401 TOKEN_EXPIRED를 주고 기록하지 않는다`() {
        `when`(memberSessionService.checkAccessToken("expired-token")).thenReturn(AccessTokenCheck.Expired)

        mockMvc.perform(post("/post/${UUID.randomUUID()}/view").header("X-Access-Token", "expired-token"))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))

        verifyNoInteractions(postService)
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
