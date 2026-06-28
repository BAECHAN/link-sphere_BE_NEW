package com.example.linksphere.domain.interaction

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import java.util.UUID
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
class InteractionController(
        private val interactionService: InteractionService,
        private val bookmarkFolderService: BookmarkFolderService
) {

    @PostMapping("/post/{postId}/like")
    fun likePost(
            @PathVariable postId: UUID,
            authentication: Authentication?
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val isLiked = interactionService.toggleLike(postId, TargetType.POST, userId)
        return ApiResponse(200, if (isLiked) "좋아요 성공" else "좋아요 취소 성공", mapOf("isLiked" to isLiked))
    }

    @PostMapping("/comment/{commentId}/like")
    fun likeComment(
            @PathVariable commentId: UUID,
            authentication: Authentication?
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val isLiked = interactionService.toggleLike(commentId, TargetType.COMMENT, userId)
        return ApiResponse(
                200,
                if (isLiked) "댓글 좋아요 성공" else "댓글 좋아요 취소 성공",
                mapOf("isLiked" to isLiked)
        )
    }

    @PostMapping("/post/{postId}/bookmark")
    fun bookmarkPost(
            @PathVariable postId: UUID,
            authentication: Authentication?
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val isBookmarked = interactionService.toggleBookmark(postId, userId)
        return ApiResponse(
                200,
                if (isBookmarked) "북마크 성공" else "북마크 취소 성공",
                mapOf("isBookmarked" to isBookmarked)
        )
    }

    @PatchMapping("/bookmark/{postId}/folder")
    fun moveBookmark(
            @PathVariable postId: UUID,
            @RequestBody request: MoveBookmarkRequest,
            authentication: Authentication?
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        bookmarkFolderService.moveBookmark(userId, postId, request.folderId)
        return ApiResponse(200, "북마크 폴더 이동 성공", Unit)
    }

    @PostMapping("/bookmark/batch/move")
    fun batchMoveBookmarks(
            @RequestBody request: BatchMoveBookmarksRequest,
            authentication: Authentication?
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val moved = bookmarkFolderService.batchMoveBookmarks(userId, request.postIds, request.folderId)
        return ApiResponse(200, "북마크 일괄 이동 성공", BatchResultResponse(moved))
    }

    @PostMapping("/bookmark/batch/delete")
    fun batchDeleteBookmarks(
            @RequestBody request: BatchDeleteBookmarksRequest,
            authentication: Authentication?
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val deleted = bookmarkFolderService.batchDeleteBookmarks(userId, request.postIds)
        return ApiResponse(200, "북마크 일괄 삭제 성공", BatchResultResponse(deleted))
    }
}
