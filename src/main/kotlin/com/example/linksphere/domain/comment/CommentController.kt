package com.example.linksphere.domain.comment

import com.example.linksphere.global.common.ApiResponse
import java.util.UUID
import org.springframework.http.MediaType
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

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

        @PostMapping("/post/{postId}/comment", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun createComment(
                @PathVariable postId: UUID,
                @RequestParam(required = false) content: String?,
                @RequestPart(required = false) image: MultipartFile?,
                @AuthenticationPrincipal principal: String?
        ): ApiResponse<CommentResponse> {
                val userId =
                        principal?.let { UUID.fromString(it) }
                                ?: throw IllegalStateException("User not authenticated")

                println("content: $content")
                println("image: $image")
                val comment = commentService.createComment(postId, userId, content, image)
                return ApiResponse(201, "댓글 작성 성공", comment)
        }

        @PostMapping("/comment/{commentId}/reply", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun createReply(
                @PathVariable commentId: UUID, // This is the parentId
                @RequestParam(required = false) content: String?,
                @RequestPart(required = false) image: MultipartFile?,
                @AuthenticationPrincipal principal: String?
        ): ApiResponse<CommentResponse> {
                val userId =
                        principal?.let { UUID.fromString(it) }
                                ?: throw IllegalStateException("User not authenticated")
                val reply = commentService.createReply(commentId, userId, content, image)
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

        @PatchMapping("/comment/{commentId}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun updateComment(
                @PathVariable commentId: UUID,
                @RequestParam(required = false) content: String?,
                @RequestPart(required = false) image: MultipartFile?,
                @AuthenticationPrincipal principal: String?
        ): ApiResponse<CommentResponse> {
                val userId =
                        principal?.let { UUID.fromString(it) }
                                ?: throw IllegalStateException("User not authenticated")
                val comment = commentService.updateComment(commentId, userId, content, image)
                return ApiResponse(200, "댓글 수정 성공", comment)
        }
}
