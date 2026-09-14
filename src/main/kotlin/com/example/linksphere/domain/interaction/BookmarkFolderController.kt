package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostPageResponse
import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@Tag(name = "북마크 폴더", description = "북마크를 담는 폴더의 생성·조회·수정·삭제·정렬")
@RestController
@RequestMapping("/bookmark/folders")
class BookmarkFolderController(private val bookmarkFolderService: BookmarkFolderService) {

    @Operation(summary = "내 폴더 목록 조회")
    @GetMapping
    fun getFolders(authentication: Authentication?): ApiResponse<FolderListResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val folders = bookmarkFolderService.getFolders(userId)
        return ApiResponse(200, "북마크 폴더 조회 성공", folders)
    }

    @Operation(
        summary = "북마크 폴더 생성",
        description = "HTTP 상태는 200 이고 본문 status 필드만 201 이다(ResponseEntity 를 쓰지 않는다). " +
            "실패: 400 INVALID_INPUT(이름 공백) · 409 DUPLICATE_FOLDER_NAME",
    )
    @PostMapping
    fun createFolder(
        @RequestBody request: CreateFolderRequest,
        authentication: Authentication?,
    ): ApiResponse<FolderResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val folder = bookmarkFolderService.createFolder(userId, request)
        return ApiResponse(201, "북마크 폴더 생성 성공", folder)
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
        authentication: Authentication?,
    ): ApiResponse<FolderResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val folder = bookmarkFolderService.updateFolder(userId, folderId, request)
        return ApiResponse(200, "북마크 폴더 수정 성공", folder)
    }

    @Operation(
        summary = "북마크 폴더 순서 변경",
        description = "folderIds 는 본인이 가진 폴더 id 전체를 중복 없이 새 순서로 담아야 한다. " +
            "실패: 400 INVALID_INPUT(id 중복·누락·본인 소유 아닌 id 포함)",
    )
    @PatchMapping("/reorder")
    fun reorderFolders(
        @RequestBody request: ReorderFoldersRequest,
        authentication: Authentication?,
    ): ApiResponse<Unit> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        bookmarkFolderService.reorderFolders(userId, request.folderIds)
        return ApiResponse(200, "북마크 폴더 순서 변경 성공", Unit)
    }

    @Operation(
        summary = "북마크 폴더 삭제",
        description = "폴더 소속만 지워지고 북마크 자체(다른 폴더 소속분)는 유지된다. " +
            "실패: 404 FOLDER_NOT_FOUND · 403 FORBIDDEN(남의 폴더)",
    )
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
        authentication: Authentication?,
    ): ApiResponse<PostPageResponse> {
        val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        val posts = bookmarkFolderService.getBookmarkedPosts(userId, folderKey, sort, search, page, size)
        return ApiResponse(200, "북마크 게시글 조회 성공", posts)
    }
}
