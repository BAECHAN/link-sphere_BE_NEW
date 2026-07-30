package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.HangulKeyboardConverter
import com.example.linksphere.domain.post.PostPageResponse
import com.example.linksphere.domain.post.PostService
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
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
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val postService: PostService,
) {

    @Transactional(readOnly = true)
    fun getFolders(userId: UUID): FolderListResponse {
        val folders = bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)
        val countByFolderId =
            bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId)
                .associate { it.folderId to it.count.toInt() }
        val uncategorizedCount = bookmarkRepository.countUncategorizedByUserId(userId).toInt()

        val folderResponses = folders.map { folder ->
            FolderResponse(
                id = folder.id,
                name = folder.name,
                sortOrder = folder.sortOrder,
                bookmarkCount = countByFolderId[folder.id] ?: 0,
                createdAt = folder.createdAt,
                updatedAt = folder.updatedAt,
            )
        }
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

        val bookmarkCount = bookmarkFolderItemRepository.countByFolderId(folder.id).toInt()
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

        // 그 폴더의 소속만 삭제 — 다른 폴더에도 있는 북마크는 그대로 유지된다.
        // FK ON DELETE CASCADE 로도 처리되지만, 통합 테스트 하네스가 없는 이 레포에서 단위 테스트로
        // 검증 가능하도록 명시적으로 먼저 지운다.
        bookmarkFolderItemRepository.deleteByFolderId(folderId)
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
     * 다중 선택 일괄 추가 — 단건 addBookmarkFolder 와 동일하게 북마크가 없으면 자동 생성한다.
     * (같은 동작이 선택 개수에 따라 의미가 달라지지 않도록 단건과 계약을 맞춘다.)
     * 반환: 처리한 postId 수.
     */
    @Transactional
    fun batchAddBookmarksToFolder(userId: UUID, folderId: UUID, postIds: List<UUID>): Int {
        if (postIds.isEmpty()) return 0

        val folder = bookmarkFolderRepository.findByIdOrNull(folderId)
            ?: throw BookmarkFolderNotFoundException(folderId)
        if (folder.userId != userId) {
            throw ForbiddenException("Cannot add bookmarks to another user's folder")
        }

        postIds.forEach { postId ->
            bookmarkRepository.insertIgnoreConflict(userId, postId)
            bookmarkFolderItemRepository.insertIgnoreConflict(userId, postId, folderId)
        }
        return postIds.size
    }

    /**
     * 다중 선택 일괄 제거 — 그 폴더에서만 뺀다 (북마크 자체는 유지).
     * 반환: 실제로 삭제된 소속 row 수.
     */
    @Transactional
    fun batchRemoveBookmarksFromFolder(userId: UUID, folderId: UUID, postIds: List<UUID>): Int {
        if (postIds.isEmpty()) return 0
        return postIds.sumOf { postId ->
            bookmarkFolderItemRepository.deleteByUserIdAndPostIdAndFolderId(userId, postId, folderId)
        }
    }

    /**
     * 다중 선택 일괄 삭제 — 본인 북마크에 한해서만. 본인 것이 아닌 ID는 무시. 소속도 함께 삭제한다.
     */
    @Transactional
    fun batchDeleteBookmarks(userId: UUID, postIds: List<UUID>): Int {
        if (postIds.isEmpty()) return 0
        val bookmarks = bookmarkRepository.findAllByUserIdAndPostIdIn(userId, postIds)
        if (bookmarks.isEmpty()) return 0
        bookmarkFolderItemRepository.deleteByUserIdAndPostIdIn(userId, postIds)
        bookmarkRepository.deleteAll(bookmarks)
        return bookmarks.size
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

        // 검색 결과가 없으면 한/영 자판 미스매칭 보정 후보로 한 번 더 검색한다 (예: spdlqj -> 네이버)
        if (postPage.totalElements == 0L && !search.isNullOrBlank()) {
            val correctedSearch = HangulKeyboardConverter.convertIfMislayout(search)
            if (correctedSearch != null) {
                val correctedPage =
                    bookmarkRepository.findBookmarkedPosts(
                        userId,
                        folderId,
                        onlyUncategorized,
                        sort ?: "latest",
                        correctedSearch,
                        pageable,
                    )
                if (correctedPage.totalElements > 0L) {
                    return PostPageResponse.from(
                        correctedPage,
                        postService.buildResponsesFromPosts(correctedPage.content, userId),
                        correctedSearch,
                    )
                }
            }
        }

        return PostPageResponse.from(postPage, postService.buildResponsesFromPosts(postPage.content, userId))
    }
}
