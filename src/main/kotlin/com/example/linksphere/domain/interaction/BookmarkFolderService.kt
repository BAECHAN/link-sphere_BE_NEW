package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostPageResponse
import com.example.linksphere.domain.post.PostService
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.BookmarkNotFoundException
import com.example.linksphere.global.exception.DuplicateFolderNameException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import org.springframework.data.domain.PageRequest
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class BookmarkFolderService(
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val postService: PostService,
) {

    @Transactional(readOnly = true)
    fun getFolders(userId: UUID): FolderListResponse {
        val folders = bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)
        val folderResponses = folders.map { folder ->
            FolderResponse(
                id = folder.id,
                name = folder.name,
                sortOrder = folder.sortOrder,
                bookmarkCount = bookmarkRepository.countByUserIdAndFolderId(userId, folder.id).toInt(),
                createdAt = folder.createdAt,
                updatedAt = folder.updatedAt,
            )
        }
        val uncategorizedCount = bookmarkRepository.countByUserIdAndFolderIdIsNull(userId).toInt()
        return FolderListResponse(folders = folderResponses, uncategorizedCount = uncategorizedCount)
    }

    @Transactional
    fun createFolder(userId: UUID, request: CreateFolderRequest): FolderResponse {
        val name = request.name.trim()
        if (name.isEmpty()) throw InvalidInputException("Folder name must not be blank")

        if (bookmarkFolderRepository.existsByUserIdAndName(userId, name)) {
            throw DuplicateFolderNameException(name)
        }

        val nextSortOrder = bookmarkFolderRepository.findMaxSortOrderByUserId(userId) + 1

        val saved = bookmarkFolderRepository.save(
            TableBookmarkFolder(
                userId = userId,
                name = name,
                sortOrder = nextSortOrder,
            ),
        )

        return FolderResponse(
            id = saved.id,
            name = saved.name,
            sortOrder = saved.sortOrder,
            bookmarkCount = 0,
            createdAt = saved.createdAt,
            updatedAt = saved.updatedAt,
        )
    }

    @Transactional
    fun updateFolder(userId: UUID, folderId: UUID, request: UpdateFolderRequest): FolderResponse {
        val folder = bookmarkFolderRepository.findByIdOrNull(folderId)
            ?: throw BookmarkFolderNotFoundException(folderId)
        if (folder.userId != userId) throw ForbiddenException("Cannot update another user's folder")

        val newName = request.name.trim()
        if (newName.isEmpty()) throw InvalidInputException("Folder name must not be blank")

        if (newName != folder.name && bookmarkFolderRepository.existsByUserIdAndName(userId, newName)) {
            throw DuplicateFolderNameException(newName)
        }

        folder.name = newName
        folder.updatedAt = LocalDateTime.now()

        val bookmarkCount = bookmarkRepository.countByUserIdAndFolderId(userId, folder.id).toInt()
        return FolderResponse(
            id = folder.id,
            name = folder.name,
            sortOrder = folder.sortOrder,
            bookmarkCount = bookmarkCount,
            createdAt = folder.createdAt,
            updatedAt = folder.updatedAt,
        )
    }

    @Transactional
    fun deleteFolder(userId: UUID, folderId: UUID) {
        val folder = bookmarkFolderRepository.findByIdOrNull(folderId)
            ?: throw BookmarkFolderNotFoundException(folderId)
        if (folder.userId != userId) throw ForbiddenException("Cannot delete another user's folder")

        // FK ON DELETE SET NULL 로 안의 북마크들의 folder_id 가 자동 NULL 처리됨 (= 미분류 이동)
        bookmarkFolderRepository.delete(folder)
    }

    /**
     * 폴더 순서 일괄 재정렬. 요청 folderIds 가 본인의 모든 폴더 ID set과 정확히 일치해야 함.
     * 누락/추가/타인 폴더 포함 시 400 INVALID_INPUT.
     */
    @Transactional
    fun reorderFolders(userId: UUID, folderIds: List<UUID>) {
        val myFolders = bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)
        val myFolderIdSet = myFolders.map { it.id }.toSet()
        val requestIdSet = folderIds.toSet()

        if (requestIdSet.size != folderIds.size) {
            throw InvalidInputException("Duplicate folder ids in reorder request")
        }
        if (requestIdSet != myFolderIdSet) {
            throw InvalidInputException("Reorder request must contain exactly the user's own folder ids")
        }

        val folderById = myFolders.associateBy { it.id }
        val now = LocalDateTime.now()
        folderIds.forEachIndexed { index, id ->
            val folder = folderById.getValue(id)
            if (folder.sortOrder != index) {
                folder.sortOrder = index
                folder.updatedAt = now
            }
        }
    }

    /**
     * 다중 선택 일괄 이동.
     * - folderId 지정 시 폴더 소유 검증 (실패 시 전체 거부, 403/404)
     * - 본인 북마크에 한해서만 이동 (postIds 중 본인 것이 아닌 ID는 무시)
     * - 반환: 실제로 처리된 row 수
     */
    @Transactional
    fun batchMoveBookmarks(userId: UUID, postIds: List<UUID>, folderId: UUID?): Int {
        if (postIds.isEmpty()) return 0

        if (folderId != null) {
            val folder = bookmarkFolderRepository.findByIdOrNull(folderId)
                ?: throw BookmarkFolderNotFoundException(folderId)
            if (folder.userId != userId) {
                throw ForbiddenException("Cannot move bookmarks to another user's folder")
            }
        }

        val bookmarks = bookmarkRepository.findAllByUserIdAndPostIdIn(userId, postIds)
        if (bookmarks.isEmpty()) return 0
        bookmarks.forEach { it.folderId = folderId }
        bookmarkRepository.saveAll(bookmarks)
        return bookmarks.size
    }

    /**
     * 다중 선택 일괄 삭제 — 본인 북마크에 한해서만. 본인 것이 아닌 ID는 무시.
     */
    @Transactional
    fun batchDeleteBookmarks(userId: UUID, postIds: List<UUID>): Int {
        if (postIds.isEmpty()) return 0
        val bookmarks = bookmarkRepository.findAllByUserIdAndPostIdIn(userId, postIds)
        if (bookmarks.isEmpty()) return 0
        bookmarkRepository.deleteAll(bookmarks)
        return bookmarks.size
    }

    /**
     * 북마크를 다른 폴더로 이동. folderId = null → 미분류.
     */
    @Transactional
    fun moveBookmark(userId: UUID, postId: UUID, folderId: UUID?) {
        val bookmark = bookmarkRepository.findById(BookmarkId(userId, postId))
            .orElseThrow { BookmarkNotFoundException(userId, postId) }

        if (folderId != null) {
            val folder = bookmarkFolderRepository.findByIdOrNull(folderId)
                ?: throw BookmarkFolderNotFoundException(folderId)
            if (folder.userId != userId) {
                throw ForbiddenException("Cannot move bookmark to another user's folder")
            }
        }

        bookmark.folderId = folderId
        bookmarkRepository.save(bookmark)
    }

    /**
     * 북마크된 게시글 페이지 조회.
     * folderKey: "all" / "uncategorized" / 폴더 UUID 문자열
     * sort: "latest"(default) / "oldest" / "title" / "views"
     * search: 제목/설명/태그 부분 검색 (null/blank면 미적용)
     */
    @Transactional(readOnly = true)
    fun getBookmarkedPosts(
        userId: UUID,
        folderKey: String,
        sort: String?,
        search: String?,
        page: Int,
        size: Int,
    ): PostPageResponse {
        val (folderId, onlyUncategorized) =
            when (folderKey) {
                "all" -> null to false
                "uncategorized" -> null to true
                else -> {
                    val uuid =
                        try {
                            UUID.fromString(folderKey)
                        } catch (e: IllegalArgumentException) {
                            throw InvalidInputException("Invalid folder key: $folderKey")
                        }
                    val folder = bookmarkFolderRepository.findByIdOrNull(uuid)
                        ?: throw BookmarkFolderNotFoundException(uuid)
                    if (folder.userId != userId) {
                        throw ForbiddenException("Cannot access another user's folder")
                    }
                    uuid to false
                }
            }

        val pageable = PageRequest.of(page, size)
        val postPage =
            bookmarkRepository.findBookmarkedPosts(
                userId,
                folderId,
                onlyUncategorized,
                sort ?: "latest",
                search,
                pageable,
            )
        return PostPageResponse.from(postPage, postService.buildResponsesFromPosts(postPage.content, userId))
    }
}
