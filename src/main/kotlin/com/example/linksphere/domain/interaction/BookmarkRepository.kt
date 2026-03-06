package com.example.linksphere.domain.interaction

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface BookmarkRepository : JpaRepository<TableBookmark, BookmarkId> {
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean
    fun deleteByUserIdAndPostId(userId: UUID, postId: UUID)
    fun countByPostId(postId: UUID): Long
    fun findAllByPostIdIn(postIds: List<UUID>): List<TableBookmark>
    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TableBookmark>
}
