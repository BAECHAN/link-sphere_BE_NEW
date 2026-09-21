package com.example.linksphere.domain.interaction

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "좋아요·북마크", description = "게시글·댓글 좋아요, 북마크 토글, 북마크-폴더 소속 관리")
@RestController
class InteractionController(
    private val interactionService: InteractionService,
    private val bookmarkFolderService: BookmarkFolderService,
) {

    @Operation(
        summary = "게시글 좋아요 토글",
        description = "이미 눌렀으면 취소된다. data 는 {\"isLiked\": true|false} 한 쌍이다(스키마에는 " +
            "additionalProperties 로만 나타난다). 실패: 404 POST_NOT_FOUND",
    )
    @PostMapping("/post/{postId}/like")
    fun likePost(
        @PathVariable postId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val isLiked = interactionService.togglePostLike(postId, userId)
        return ApiResponse(200, if (isLiked) "좋아요 성공" else "좋아요 취소 성공", mapOf("isLiked" to isLiked))
    }

    @Operation(
        summary = "댓글 좋아요 토글",
        description = "이미 눌렀으면 취소된다. data 는 {\"isLiked\": true|false} 한 쌍이다. " +
            "실패: 404 POST_NOT_FOUND(글 없음·비공개) · 400 INVALID_INPUT(삭제된 댓글)",
    )
    @PostMapping("/comment/{commentId}/like")
    fun likeComment(
        @PathVariable commentId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val isLiked = interactionService.toggleCommentLike(commentId, userId)
        return ApiResponse(
            200,
            if (isLiked) "댓글 좋아요 성공" else "댓글 좋아요 취소 성공",
            mapOf("isLiked" to isLiked),
        )
    }

    @Operation(
        summary = "게시글 북마크 토글",
        description = "이미 저장했으면 취소되고(모든 폴더 소속도 함께 삭제) 폴더 소속은 초기화된다. " +
            "data 는 {\"isBookmarked\": true|false} 한 쌍이다. 실패: 404 POST_NOT_FOUND",
    )
    @PostMapping("/post/{postId}/bookmark")
    fun bookmarkPost(
        @PathVariable postId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Map<String, Boolean>> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val isBookmarked = interactionService.toggleBookmark(postId, userId)
        return ApiResponse(
            200,
            if (isBookmarked) "북마크 성공" else "북마크 취소 성공",
            mapOf("isBookmarked" to isBookmarked),
        )
    }

    @Operation(
        summary = "북마크를 폴더에 추가",
        description = "북마크가 안 돼 있었다면 함께 생성한다. 실패: 404 POST_NOT_FOUND · " +
            "404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더)",
    )
    @PostMapping("/bookmark/{postId}/folders/{folderId}")
    fun addBookmarkFolder(
        @PathVariable postId: UUID,
        @PathVariable folderId: UUID,
        authentication: Authentication?,
    ): ApiResponse<BookmarkFoldersResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val result = interactionService.addBookmarkFolder(postId, folderId, userId)
        return ApiResponse(200, "폴더에 저장 성공", result)
    }

    @Operation(summary = "북마크를 폴더에서 제거", description = "북마크 자체는 유지되고 그 폴더 소속만 지워진다.")
    @DeleteMapping("/bookmark/{postId}/folders/{folderId}")
    fun removeBookmarkFolder(
        @PathVariable postId: UUID,
        @PathVariable folderId: UUID,
        authentication: Authentication?,
    ): ApiResponse<BookmarkFoldersResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val result = interactionService.removeBookmarkFolder(postId, folderId, userId)
        return ApiResponse(200, "폴더에서 제거 성공", result)
    }

    @Operation(summary = "북마크의 폴더 소속 전체 해제", description = "북마크 자체는 유지되고 모든 폴더 소속만 지워진다(미분류로 이동).")
    @DeleteMapping("/bookmark/{postId}/folders")
    fun clearBookmarkFolders(
        @PathVariable postId: UUID,
        authentication: Authentication?,
    ): ApiResponse<BookmarkFoldersResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val result = interactionService.clearBookmarkFolders(postId, userId)
        return ApiResponse(200, "폴더 소속 전체 해제 성공", result)
    }

    @Operation(
        summary = "여러 게시글을 폴더에 일괄 추가",
        description = "data.processedCount 는 요청에 담긴 postId 개수 그대로다 — 이미 저장돼 있던 " +
            "postId 도 포함해서 센다(중복 여부를 걸러내지 않는다).",
    )
    @PostMapping("/bookmark/batch/folders/{folderId}/add")
    fun batchAddBookmarksToFolder(
        @PathVariable folderId: UUID,
        @RequestBody request: BatchFolderBookmarksRequest,
        authentication: Authentication?,
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val added = bookmarkFolderService.batchAddBookmarksToFolder(userId, folderId, request.postIds)
        return ApiResponse(200, "북마크 일괄 추가 성공", BatchResultResponse(added))
    }

    @Operation(summary = "여러 게시글을 폴더에서 일괄 제거", description = "data.processedCount 는 실제로 삭제된 행 수다.")
    @PostMapping("/bookmark/batch/folders/{folderId}/remove")
    fun batchRemoveBookmarksFromFolder(
        @PathVariable folderId: UUID,
        @RequestBody request: BatchFolderBookmarksRequest,
        authentication: Authentication?,
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val removed = bookmarkFolderService.batchRemoveBookmarksFromFolder(userId, folderId, request.postIds)
        return ApiResponse(200, "북마크 일괄 제거 성공", BatchResultResponse(removed))
    }

    @Operation(
        summary = "여러 북마크 일괄 삭제",
        description = "폴더 구분 없이 북마크 자체를 지운다. 본인 소유가 아닌 postId 는 조용히 무시된다. " +
            "data.processedCount 는 실제로 삭제된 개수(무시된 것 제외)다.",
    )
    @PostMapping("/bookmark/batch/delete")
    fun batchDeleteBookmarks(
        @RequestBody request: BatchDeleteBookmarksRequest,
        authentication: Authentication?,
    ): ApiResponse<BatchResultResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val deleted = bookmarkFolderService.batchDeleteBookmarks(userId, request.postIds)
        return ApiResponse(200, "북마크 일괄 삭제 성공", BatchResultResponse(deleted))
    }
}
