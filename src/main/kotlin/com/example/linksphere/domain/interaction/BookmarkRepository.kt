package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BookmarkRepository :
    JpaRepository<TableBookmark, BookmarkId>,
    BookmarkRepositoryCustom {
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean
    fun deleteByUserIdAndPostId(userId: UUID, postId: UUID)
    fun countByPostId(postId: UUID): Long
    fun findAllByPostIdIn(postIds: List<UUID>): List<TableBookmark>
    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TableBookmark>

    // 폴더별 북마크 수 — Stage 1 폴더 목록 응답에 사용
    fun countByUserIdAndFolderId(userId: UUID, folderId: UUID): Long
}
