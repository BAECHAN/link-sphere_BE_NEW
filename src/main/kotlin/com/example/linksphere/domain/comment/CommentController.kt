package com.example.linksphere.domain.comment

import com.example.linksphere.global.common.ApiResponse
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
class CommentController(private val commentService: CommentService) {

    private fun String?.toRequiredUserId(): UUID = this?.let { UUID.fromString(it) } ?: throw IllegalStateException("User not authenticated")

    // 비로그인 시 Security가 principal에 "anonymousUser" 문자열을 주입하므로 UUID 파싱 실패 → null 처리
    private fun String?.toOptionalUserId(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    @GetMapping("/post/{postId}/comment")
    fun getComments(
        @PathVariable postId: UUID,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<List<CommentResponse>> = ApiResponse(200, "댓글 조회 성공", commentService.getComments(postId, principal.toOptionalUserId()))

    @PostMapping("/post/{postId}/comment", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createComment(
        @PathVariable postId: UUID,
        @RequestParam(required = false) content: String?,
        @RequestParam(required = false) images: List<MultipartFile>?,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<CommentResponse> {
        val comment = commentService.createComment(postId, principal.toRequiredUserId(), content, images)
        return ApiResponse(201, "댓글 작성 성공", comment)
    }

    @PostMapping("/comment/{commentId}/reply", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun createReply(
        @PathVariable commentId: UUID,
        @RequestParam(required = false) content: String?,
        @RequestParam(required = false) images: List<MultipartFile>?,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<CommentResponse> = ApiResponse(201, "답글 작성 성공", commentService.createReply(commentId, principal.toRequiredUserId(), content, images))

    @DeleteMapping("/comment/{commentId}")
    fun deleteComment(
        @PathVariable commentId: UUID,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<Unit> {
        commentService.deleteComment(commentId, principal.toRequiredUserId())
        return ApiResponse(200, "댓글 삭제 성공", Unit)
    }

    @PatchMapping("/comment/{commentId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun updateComment(
        @PathVariable commentId: UUID,
        @RequestParam(required = false) content: String?,
        @RequestParam(required = false) images: List<MultipartFile>?,
        @AuthenticationPrincipal principal: String?,
    ): ApiResponse<CommentResponse> = ApiResponse(200, "댓글 수정 성공", commentService.updateComment(commentId, principal.toRequiredUserId(), content, images))
}
