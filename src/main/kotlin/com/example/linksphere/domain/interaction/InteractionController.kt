package com.example.linksphere.domain.interaction

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
class InteractionController(
    private val interactionService: InteractionService,
    private val bookmarkFolderService: BookmarkFolderService,
) {

    @PostMapping("/post/{postId}/like")
    fun likePost(
        @PathVariable postId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val isLiked = interactionService.togglePostLike(postId, userId)
        return ApiResponse(200, if (isLiked) "좋아요 성공" else "좋아요 취소 성공", mapOf("isLiked" to isLiked))
    }

    @PostMapping("/comment/{commentId}/like")
    fun likeComment(
        @PathVariable commentId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val isLiked = interactionService.toggleCommentLike(commentId, userId)
        return ApiResponse(
            200,
            if (isLiked) "댓글 좋아요 성공" else "댓글 좋아요 취소 성공",
            mapOf("isLiked" to isLiked),
        )
    }

    @PostMapping("/post/{postId}/bookmark")
    fun bookmarkPost(
        @PathVariable postId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val isBookmarked = interactionService.toggleBookmark(postId, userId)
        return ApiResponse(
            200,
            if (isBookmarked) "북마크 성공" else "북마크 취소 성공",
            mapOf("isBookmarked" to isBookmarked),
        )
    }

    @PostMapping("/bookmark/{postId}/folders/{folderId}")
    fun addBookmarkFolder(
        @PathVariable postId: UUID,
        @PathVariable folderId: UUID,
        authentication: Authentication?,
    ): ApiResponse<BookmarkFoldersResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val result = interactionService.addBookmarkFolder(postId, folderId, userId)
        return ApiResponse(200, "폴더에 저장 성공", result)
    }

    @DeleteMapping("/bookmark/{postId}/folders/{folderId}")
    fun removeBookmarkFolder(
        @PathVariable postId: UUID,
        @PathVariable folderId: UUID,
        authentication: Authentication?,
    ): ApiResponse<BookmarkFoldersResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val result = interactionService.removeBookmarkFolder(postId, folderId, userId)
        return ApiResponse(200, "폴더에서 제거 성공", result)
    }

    @DeleteMapping("/bookmark/{postId}/folders")
    fun clearBookmarkFolders(
        @PathVariable postId: UUID,
        authentication: Authentication?,
    ): ApiResponse<BookmarkFoldersResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val result = interactionService.clearBookmarkFolders(postId, userId)
        return ApiResponse(200, "폴더 소속 전체 해제 성공", result)
    }

    @PostMapping("/bookmark/batch/folders/{folderId}/add")
    fun batchAddBookmarksToFolder(
        @PathVariable folderId: UUID,
        @RequestBody request: BatchFolderBookmarksRequest,
        authentication: Authentication?,
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val added = bookmarkFolderService.batchAddBookmarksToFolder(userId, folderId, request.postIds)
        return ApiResponse(200, "북마크 일괄 추가 성공", BatchResultResponse(added))
    }

    @PostMapping("/bookmark/batch/folders/{folderId}/remove")
    fun batchRemoveBookmarksFromFolder(
        @PathVariable folderId: UUID,
        @RequestBody request: BatchFolderBookmarksRequest,
        authentication: Authentication?,
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val removed = bookmarkFolderService.batchRemoveBookmarksFromFolder(userId, folderId, request.postIds)
        return ApiResponse(200, "북마크 일괄 제거 성공", BatchResultResponse(removed))
    }

    @PostMapping("/bookmark/batch/delete")
    fun batchDeleteBookmarks(
        @RequestBody request: BatchDeleteBookmarksRequest,
        authentication: Authentication?,
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val deleted = bookmarkFolderService.batchDeleteBookmarks(userId, request.postIds)
        return ApiResponse(200, "북마크 일괄 삭제 성공", BatchResultResponse(deleted))
    }
}
