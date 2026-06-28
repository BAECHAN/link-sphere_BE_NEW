package com.example.linksphere.domain.interaction

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BookmarkFolderRepository : JpaRepository<TableBookmarkFolder, UUID> {

    fun findByUserIdOrderBySortOrderAsc(userId: UUID): List<TableBookmarkFolder>

    fun existsByUserIdAndName(userId: UUID, name: String): Boolean

    fun existsByIdAndUserId(id: UUID, userId: UUID): Boolean

    @Query("SELECT COALESCE(MAX(f.sortOrder), -1) FROM TableBookmarkFolder f WHERE f.userId = :userId")
    fun findMaxSortOrderByUserId(@Param("userId") userId: UUID): Int
}
