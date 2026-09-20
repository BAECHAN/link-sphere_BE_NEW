package com.example.linksphere.domain.comment

import com.example.linksphere.global.common.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "댓글", description = "댓글·답글 작성·조회·수정·삭제")
@RestController
class CommentController(private val commentService: CommentService) {

    private fun String?.toRequiredUserId(): UUID = this?.let { UUID.fromString(it) } ?: throw IllegalStateException("User not authenticated")

    // 비로그인 시 Security가 principal에 "anonymousUser" 문자열을 주입하므로 UUID 파싱 실패 → null 처리
    private fun String?.toOptionalUserId(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    @Operation(
        summary = "댓글 목록 조회",
        description = "JWT 없이도 호출 가능하다. 비공개 글의 댓글은 작성자만 볼 수 있고, 그 외에는 " +
            "글이 없는 것처럼 404 로 응답한다. 실패: 404 POST_NOT_FOUND",
    )
    @SecurityRequirements
    @GetMapping("/post/{postId}/comment")
    fun getComments(
        @PathVariable postId: UUID,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<List<CommentResponse>> = ApiResponse(200, "댓글 조회 성공", commentService.getComments(postId, principal.toOptionalUserId()))

    @Operation(
        summary = "내 댓글 목록 조회",
        description = "로그인한 사용자가 작성한 댓글을 최신순으로 페이지네이션 조회한다. 삭제된(톰스톤) " +
            "댓글은 제외한다. 댓글을 단 뒤 원글이 비공개로 전환되면 본인 소유가 아닌 한 목록에서도 제외된다.",
    )
    @GetMapping("/comment/my")
    fun getMyComments(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<MyCommentPageResponse> = ApiResponse(200, "내 댓글 목록 조회 성공", commentService.getMyComments(principal.toRequiredUserId(), page, size))

    @Operation(
        summary = "댓글 작성",
        description = "HTTP 상태는 200 이고 본문 status 필드만 201 이다. content·images 가 둘 다 " +
            "비어 있으면 400 이 아니라 404 NOT_FOUND 로 응답한다(IllegalArgumentException 공통 매핑). " +
            "실패: 404 POST_NOT_FOUND · 404 NOT_FOUND(내용 없음) · 400 INVALID_INPUT(이미지 개수·본문 길이 초과)",
    )
    @PostMapping("/post/{postId}/comment")
    fun createComment(
        @PathVariable postId: UUID,
        @RequestBody request: CreateCommentRequest,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<CommentResponse> {
        val comment = commentService.createComment(postId, principal.toRequiredUserId(), request.content, request.images)
        return ApiResponse(201, "댓글 작성 성공", comment)
    }

    @Operation(
        summary = "답글 작성",
        description = "답글의 답글은 허용하지 않는다(최대 depth 1). HTTP 상태는 200 이고 본문 status " +
            "필드만 201 이다. 실패: 404 NOT_FOUND(부모 댓글 없음·depth 초과·다른 글의 댓글) · " +
            "400 INVALID_INPUT(이미지 개수·본문 길이 초과)",
    )
    @PostMapping("/comment/{commentId}/reply")
    fun createReply(
        @PathVariable commentId: UUID,
        @RequestBody request: CreateCommentRequest,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<CommentResponse> = ApiResponse(201, "답글 작성 성공", commentService.createReply(commentId, principal.toRequiredUserId(), request.content, request.images))

    @Operation(
        summary = "댓글 삭제",
        description = "실패: 404 NOT_FOUND(댓글 없음) · 403 FORBIDDEN(작성자가 아님)",
    )
    @DeleteMapping("/comment/{commentId}")
    fun deleteComment(
        @PathVariable commentId: UUID,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<Unit> {
        commentService.deleteComment(commentId, principal.toRequiredUserId())
        return ApiResponse(200, "댓글 삭제 성공", Unit)
    }

    @Operation(
        summary = "댓글 수정",
        description = "실패: 404 NOT_FOUND(댓글 없음) · 400 INVALID_INPUT(이미지 개수·본문 길이 초과) · " +
            "403 FORBIDDEN(작성자가 아님). 이미 삭제된 댓글이면 409가 아니라 500 " +
            "INTERNAL_SERVER_ERROR 로 응답한다(IllegalStateException 전용 핸들러가 없음 — 알려진 결함, 이 수정 범위 밖).",
    )
    @PatchMapping("/comment/{commentId}")
    fun updateComment(
        @PathVariable commentId: UUID,
        @RequestBody request: CreateCommentRequest,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<CommentResponse> = ApiResponse(200, "댓글 수정 성공", commentService.updateComment(commentId, principal.toRequiredUserId(), request.content, request.images))
}
