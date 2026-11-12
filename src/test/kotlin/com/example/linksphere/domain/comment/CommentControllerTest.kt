package com.example.linksphere.domain.comment

import com.example.linksphere.domain.auth.AccessTokenCheck
import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.member.MemberRole
import com.example.linksphere.global.config.SecurityConfig
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import com.example.linksphere.global.exception.DuplicateCommentException
import com.example.linksphere.global.exception.InvalidInputException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime
import java.util.UUID

/**
 * CommentController가 사용자 id를 Authentication + getUserId()로 꺼내 서비스에 넘기는지 실제 SecurityConfig·
 * SessionAuthenticationFilter로 검증한다(예전에는 @AuthenticationPrincipal String? + 자체 UUID 파싱이었다). 토큰 없는
 * 공개 조회는 비로그인(null)으로, 유효 토큰은 그 세션의 회원 id로 위임돼야 한다. 구성은 PostSecurityTest와 같다.
 */
@WebMvcTest(controllers = [CommentController::class])
@Import(SecurityConfig::class, CustomAuthenticationEntryPoint::class, CustomAccessDeniedHandler::class)
class CommentControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @MockitoBean private lateinit var commentService: CommentService

    @Test
    fun `토큰 없이 댓글 목록을 조회하면 비로그인(null)으로 위임한다`() {
        val postId = UUID.randomUUID()
        `when`(commentService.getComments(postId, null)).thenReturn(emptyList())

        mockMvc.perform(get("/post/$postId/comment"))
            .andExpect(status().isOk)

        verify(commentService).getComments(postId, null)
    }

    @Test
    fun `유효 토큰으로 댓글 목록을 조회하면 그 회원 id로 위임한다`() {
        val postId = UUID.randomUUID()
        val memberId = givenValidToken()
        `when`(commentService.getComments(postId, memberId)).thenReturn(emptyList())

        mockMvc.perform(get("/post/$postId/comment").header("X-Access-Token", "valid-token"))
            .andExpect(status().isOk)

        verify(commentService).getComments(postId, memberId)
    }

    @Test
    fun `유효 토큰으로 댓글을 작성하면 그 회원 id로 위임하고 HTTP 상태와 본문 status가 모두 201이다`() {
        val postId = UUID.randomUUID()
        val memberId = givenValidToken()
        val key = UUID.randomUUID()
        `when`(commentService.createComment(postId, memberId, "내용", null, key)).thenReturn(comment(memberId))

        mockMvc.perform(
            post("/post/$postId/comment")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value(201))

        verify(commentService).createComment(postId, memberId, "내용", null, key)
    }

    @Test
    fun `같은 키의 댓글이 이미 있으면 새로 만들지 않고 그 댓글을 201로 돌려준다`() {
        val postId = UUID.randomUUID()
        val memberId = givenValidToken()
        val key = UUID.randomUUID()
        val existing = comment(memberId)
        `when`(commentService.findByIdempotencyKey(memberId, key)).thenReturn(existing)

        mockMvc.perform(
            post("/post/$postId/comment")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.id").value(existing.id.toString()))

        verify(commentService, never()).createComment(postId, memberId, "내용", null, key)
    }

    @Test
    fun `다른 키로 같은 댓글을 1분 안에 쓰면 409 DUPLICATE_COMMENT를 준다`() {
        val postId = UUID.randomUUID()
        val memberId = givenValidToken()
        val key = UUID.randomUUID()
        `when`(commentService.createComment(postId, memberId, "내용", null, key)).thenThrow(DuplicateCommentException())

        mockMvc.perform(
            post("/post/$postId/comment")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_COMMENT"))
    }

    @Test
    fun `다른 키로 같은 답글을 1분 안에 쓰면 409 DUPLICATE_COMMENT를 준다`() {
        val parentId = UUID.randomUUID()
        val memberId = givenValidToken()
        val key = UUID.randomUUID()
        `when`(commentService.createReply(parentId, memberId, "내용", null, key)).thenThrow(DuplicateCommentException())

        mockMvc.perform(
            post("/comment/$parentId/reply")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_COMMENT"))
    }

    @Test
    fun `키 없이 댓글을 작성하면 서비스까지 가지 않고 400 INVALID_INPUT을 준다`() {
        givenValidToken()

        mockMvc.perform(
            post("/post/${UUID.randomUUID()}/comment")
                .header("X-Access-Token", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))

        verifyNoInteractions(commentService)
    }

    @Test
    fun `키 없이 답글을 작성하면 서비스까지 가지 않고 400 INVALID_INPUT을 준다`() {
        givenValidToken()

        mockMvc.perform(
            post("/comment/${UUID.randomUUID()}/reply")
                .header("X-Access-Token", "valid-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))

        verifyNoInteractions(commentService)
    }

    @Test
    fun `키가 UUID가 아니면 400을 준다`() {
        givenValidToken()

        mockMvc.perform(
            post("/post/${UUID.randomUUID()}/comment")
                .header("X-Access-Token", "valid-token")
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isBadRequest)

        verifyNoInteractions(commentService)
    }

    @Test
    fun `유효 토큰으로 댓글을 삭제하면 그 회원 id로 위임한다`() {
        val commentId = UUID.randomUUID()
        val memberId = givenValidToken()

        mockMvc.perform(delete("/comment/$commentId").header("X-Access-Token", "valid-token"))
            .andExpect(status().isOk)

        verify(commentService).deleteComment(commentId, memberId)
    }

    @Test
    fun `토큰 없이 댓글을 작성하면 서비스까지 가지 않고 401을 준다`() {
        mockMvc.perform(
            post("/post/${UUID.randomUUID()}/comment")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(CreateCommentRequest(content = "내용"))),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("NOT_LOGGED_IN"))
    }

    @Test
    fun `내 댓글은 cursor로 읽는다`() {
        val memberId = givenValidToken()
        `when`(commentService.getMyCommentsByCursor(memberId, "next", 10)).thenReturn(MyCommentPageResponse(emptyList(), nextCursor = "after-next"))

        mockMvc.perform(get("/comment/my").param("cursor", "next").header("X-Access-Token", "valid-token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nextCursor").value("after-next"))
    }

    // cursor 전환 전 번들이 보내는 값 - PostSecurityTest의 같은 테스트 참고
    @Test
    fun `내 댓글은 page를 보내면 400 INVALID_INPUT을 주고 목록을 읽지 않는다`() {
        givenValidToken()

        mockMvc.perform(get("/comment/my").param("page", "NaN").header("X-Access-Token", "valid-token"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))

        verifyNoInteractions(commentService)
    }

    @Test
    fun `내 댓글의 잘못된 cursor는 400 INVALID_INPUT을 준다`() {
        val memberId = givenValidToken()
        `when`(commentService.getMyCommentsByCursor(memberId, "broken", 10)).thenThrow(InvalidInputException("Invalid cursor"))

        mockMvc.perform(get("/comment/my").param("cursor", "broken").header("X-Access-Token", "valid-token"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    private fun givenValidToken(): UUID {
        val memberId = UUID.randomUUID()
        `when`(memberSessionService.checkAccessToken("valid-token")).thenReturn(AccessTokenCheck.Valid(memberId, UUID.randomUUID(), MemberRole.USER))
        return memberId
    }

    private fun comment(authorId: UUID) = CommentResponse(
        id = UUID.randomUUID(),
        content = "내용",
        isDeleted = false,
        author = CommentAuthor(authorId, "작성자", null),
        createdAt = LocalDateTime.now(),
    )
}
