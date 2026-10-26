package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostPageResponse
import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "북마크 폴더", description = "북마크를 담는 폴더의 생성·조회·수정·삭제")
@RestController
@RequestMapping("/bookmark/folders")
class BookmarkFolderController(private val bookmarkFolderService: BookmarkFolderService) {

    @Operation(summary = "내 폴더 목록 조회")
    @GetMapping
    fun getFolders(authentication: Authentication): ApiResponse<FolderListResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val folders = bookmarkFolderService.getFolders(userId)
        return ApiResponse(HttpStatus.OK.value(), "북마크 폴더 조회 성공", folders)
    }

    @Operation(
        summary = "북마크 폴더 생성",
        description = "HTTP 상태와 본문 status 모두 201 이다. " +
            "실패: 400 INVALID_INPUT(이름 공백) · 409 DUPLICATE_FOLDER_NAME",
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createFolder(
        @RequestBody request: CreateFolderRequest,
        authentication: Authentication,
    ): ApiResponse<FolderResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val folder = bookmarkFolderService.createFolder(userId, request)
        return ApiResponse(HttpStatus.CREATED.value(), "북마크 폴더 생성 성공", folder)
    }

    @Operation(
        summary = "북마크 폴더 이름 수정",
        description = "실패: 404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더) · " +
            "400 INVALID_INPUT(이름 공백) · 409 DUPLICATE_FOLDER_NAME",
    )
    @PatchMapping("/{folderId}")
    fun updateFolder(
        @PathVariable folderId: UUID,
        @RequestBody request: UpdateFolderRequest,
        authentication: Authentication,
    ): ApiResponse<FolderResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val folder = bookmarkFolderService.updateFolder(userId, folderId, request)
        return ApiResponse(HttpStatus.OK.value(), "북마크 폴더 수정 성공", folder)
    }

    @Operation(
        summary = "북마크 폴더 고정",
        description = "이미 고정된 상태여도 200 이다(멱등). 처음 고정한 시각을 유지한다 — 고정 구획은 이 시각 오름차순이다. " +
            "실패: 404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더)",
    )
    @PutMapping("/{folderId}/pin")
    fun putFolderPin(
        @PathVariable folderId: UUID,
        authentication: Authentication,
    ): ApiResponse<FolderPinResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val result = bookmarkFolderService.setFolderPinned(userId, folderId, pinned = true)
        return ApiResponse(HttpStatus.OK.value(), "북마크 폴더 고정 성공", result)
    }

    @Operation(
        summary = "북마크 폴더 고정 해제",
        description = "고정 안 된 상태여도 200 이다(멱등). 실패: 404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더)",
    )
    @DeleteMapping("/{folderId}/pin")
    fun deleteFolderPin(
        @PathVariable folderId: UUID,
        authentication: Authentication,
    ): ApiResponse<FolderPinResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val result = bookmarkFolderService.setFolderPinned(userId, folderId, pinned = false)
        return ApiResponse(HttpStatus.OK.value(), "북마크 폴더 고정 해제 성공", result)
    }

    @Operation(
        summary = "북마크 폴더 삭제",
        description = "폴더 소속만 지워지고 북마크 자체(다른 폴더 소속분)는 유지된다. " +
            "실패: 404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더)",
    )
    @DeleteMapping("/{folderId}")
    fun deleteFolder(
        @PathVariable folderId: UUID,
        authentication: Authentication,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        bookmarkFolderService.deleteFolder(userId, folderId)
        return ApiResponse(HttpStatus.OK.value(), "북마크 폴더 삭제 성공", Unit)
    }

    /**
     * 북마크 폴더의 게시글 목록.
     * folderKey: "all" / "uncategorized" / 폴더 UUID
     */
    @Operation(
        summary = "폴더별 북마크 게시글 조회",
        description = "folderKey 는 all(전체) · uncategorized(미분류) · 폴더 UUID 중 하나다. " +
            "실패: 400 INVALID_INPUT(잘못된 key) · 403 FORBIDDEN(남의 폴더) · 404 FOLDER_NOT_FOUND",
    )
    @GetMapping("/{folderKey}/posts")
    fun getBookmarkedPosts(
        @PathVariable folderKey: String,
        @RequestParam(required = false) sort: String?,
        @RequestParam(required = false) search: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        authentication: Authentication,
    ): ApiResponse<PostPageResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        val posts = bookmarkFolderService.getBookmarkedPosts(userId, folderKey, sort, search, page, size)
        return ApiResponse(HttpStatus.OK.value(), "북마크 게시글 조회 성공", posts)
    }
}
