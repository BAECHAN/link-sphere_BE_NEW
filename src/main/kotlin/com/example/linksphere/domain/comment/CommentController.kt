package com.example.linksphere.domain.comment

import com.example.linksphere.global.common.ApiResponse
import java.util.UUID
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
class CommentController(private val commentService: CommentService) {

    @GetMapping("/post/{postId}/comment")
    fun getComments(
            @PathVariable postId: UUID,
            @AuthenticationPrincipal principal: String?
    ): ApiResponse<List<CommentResponse>> {
        val userId = principal?.let { UUID.fromString(it) }
        val comments = commentService.getComments(postId, userId)
        return ApiResponse(200, "댓글 조회 성공", comments)
    }

    @PostMapping("/post/{postId}/comment")
    fun createComment(
            @PathVariable postId: UUID,
            @RequestBody request: CreateCommentRequest,
            @AuthenticationPrincipal principal: String?
    ): ApiResponse<CommentResponse> {
        val userId =
                principal?.let { UUID.fromString(it) }
                        ?: throw IllegalStateException("User not authenticated")
        val comment = commentService.createComment(postId, userId, request.content)
        return ApiResponse(201, "댓글 작성 성공", comment)
    }

    @PostMapping("/comment/{commentId}/reply")
    fun createReply(
            @PathVariable commentId: UUID, // This is the parentId
            @RequestBody request: CreateCommentRequest,
            @AuthenticationPrincipal principal: String?
    ): ApiResponse<CommentResponse> {
        val userId =
                principal?.let { UUID.fromString(it) }
                        ?: throw IllegalStateException("User not authenticated")
        val reply = commentService.createReply(commentId, userId, request.content)
        return ApiResponse(201, "답글 작성 성공", reply)
    }

    @DeleteMapping("/comment/{commentId}")
    fun deleteComment(
            @PathVariable commentId: UUID,
            @AuthenticationPrincipal principal: String?
    ): ApiResponse<Unit> {
        val userId =
                principal?.let { UUID.fromString(it) }
                        ?: throw IllegalStateException("User not authenticated")
        commentService.deleteComment(commentId, userId)
        return ApiResponse(200, "댓글 삭제 성공", Unit)
    }
}
