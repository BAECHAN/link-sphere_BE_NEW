package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostPageResponse
import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/bookmark/folders")
class BookmarkFolderController(private val bookmarkFolderService: BookmarkFolderService) {

    @GetMapping
    fun getFolders(authentication: Authentication?): ApiResponse<List<FolderResponse>> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val folders = bookmarkFolderService.getFolders(userId)
        return ApiResponse(200, "북마크 폴더 조회 성공", folders)
    }

    @PostMapping
    fun createFolder(
        @RequestBody request: CreateFolderRequest,
        authentication: Authentication?,
    ): ApiResponse<FolderResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val folder = bookmarkFolderService.createFolder(userId, request)
        return ApiResponse(201, "북마크 폴더 생성 성공", folder)
    }

    @PatchMapping("/{folderId}")
    fun updateFolder(
        @PathVariable folderId: UUID,
        @RequestBody request: UpdateFolderRequest,
        authentication: Authentication?,
    ): ApiResponse<FolderResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val folder = bookmarkFolderService.updateFolder(userId, folderId, request)
        return ApiResponse(200, "북마크 폴더 수정 성공", folder)
    }

    @PatchMapping("/reorder")
    fun reorderFolders(
        @RequestBody request: ReorderFoldersRequest,
        authentication: Authentication?,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        bookmarkFolderService.reorderFolders(userId, request.folderIds)
        return ApiResponse(200, "북마크 폴더 순서 변경 성공", Unit)
    }

    @DeleteMapping("/{folderId}")
    fun deleteFolder(
        @PathVariable folderId: UUID,
        authentication: Authentication?,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        bookmarkFolderService.deleteFolder(userId, folderId)
        return ApiResponse(200, "북마크 폴더 삭제 성공", Unit)
    }

    /**
     * 북마크 폴더의 게시글 목록.
     * folderKey: "all" / "uncategorized" / 폴더 UUID
     */
    @GetMapping("/{folderKey}/posts")
    fun getBookmarkedPosts(
        @PathVariable folderKey: String,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        authentication: Authentication?,
    ): ApiResponse<PostPageResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val posts = bookmarkFolderService.getBookmarkedPosts(userId, folderKey, sort, search, page, size)
        return ApiResponse(200, "북마크 게시글 조회 성공", posts)
    }
}
