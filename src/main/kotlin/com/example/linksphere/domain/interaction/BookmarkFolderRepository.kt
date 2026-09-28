package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface BookmarkFolderRepository : JpaRepository<TableBookmarkFolder, UUID> {

    fun findByUserIdOrderBySortOrderAsc(userId: UUID): List<TableBookmarkFolder>

    fun existsByUserIdAndName(userId: UUID, name: String): Boolean

    fun existsByIdAndUserId(id: UUID, userId: UUID): Boolean

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM TableBookmarkFolder f WHERE f.userId = :userId")
    fun findMaxSortOrderByUserId(@Param("userId") userId: UUID): Int

    // 회원탈퇴 시(AccountDeletionService) 그 회원의 폴더를 전부 지운다 - 폴더 아이템을
    // 먼저 지운 뒤 호출해야 한다(BookmarkFolderService.deleteFolder와 같은 순서).
    fun deleteByUserId(userId: UUID)
}
