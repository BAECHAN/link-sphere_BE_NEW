package com.example.linksphere.domain.comment

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

/**
 * 댓글 좋아요·취소. 댓글의 데이터라 comment에 두지만 API 문서에서는 지금처럼 "좋아요·북마크" 묶음에 보이게 한다 -
 * 태그 이름과 메서드 이름(operationId)을 interaction의 InteractionController에 있을 때 그대로 둬 스펙이 바뀌지 않는다
 * (docs/plans/2026-11-16-be-structure-stage2.md PR-5). 태그 설명은 InteractionController에 있다.
 */
@Tag(name = "좋아요·북마크")
@RestController
class CommentLikeController(private val commentService: CommentService) {

    @Operation(
        summary = "댓글 좋아요",
        description = "이미 좋아요한 상태여도 200 이다(멱등). data 는 {\"isLiked\": true} 다. " +
            "실패: 404 COMMENT_NOT_FOUND(댓글 없음) · 404 POST_NOT_FOUND(글 없음·비공개) · 400 INVALID_INPUT(삭제된 댓글)",
    )
    @PutMapping("/comment/{commentId}/like")
    fun putCommentLike(
        @PathVariable commentId: UUID,
        authentication: Authentication,
    ): ApiResponse<CommentLikeResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val isLiked = commentService.setCommentLike(commentId, userId, liked = true)
        return ApiResponse(HttpStatus.OK.value(), "댓글 좋아요 성공", CommentLikeResponse(isLiked))
    }

    @Operation(
        summary = "댓글 좋아요 취소",
        description = "좋아요하지 않은 상태여도 200 이다(멱등). data 는 {\"isLiked\": false} 다. " +
            "실패: 404 COMMENT_NOT_FOUND(댓글 없음) · 404 POST_NOT_FOUND(글 없음·비공개) · 400 INVALID_INPUT(삭제된 댓글)",
    )
    @DeleteMapping("/comment/{commentId}/like")
    fun deleteCommentLike(
        @PathVariable commentId: UUID,
        authentication: Authentication,
    ): ApiResponse<CommentLikeResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val isLiked = commentService.setCommentLike(commentId, userId, liked = false)
        return ApiResponse(HttpStatus.OK.value(), "댓글 좋아요 취소 성공", CommentLikeResponse(isLiked))
    }
}
