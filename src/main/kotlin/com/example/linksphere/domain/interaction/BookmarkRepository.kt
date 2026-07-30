package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface BookmarkRepository :
    JpaRepository<TableBookmark, BookmarkId>,
    BookmarkRepositoryCustom {
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean
    fun deleteByUserIdAndPostId(userId: UUID, postId: UUID)
    fun countByPostId(postId: UUID): Long
    fun findAllByPostIdIn(postIds: List<UUID>): List<TableBookmark>
    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TableBookmark>

    // 미분류(bookmark_folder_items 소속 0개) 북마크 수 — 폴더 목록 응답에 사용
    @Query(
        "SELECT COUNT(b) FROM TableBookmark b WHERE b.userId = :userId " +
            "AND NOT EXISTS (SELECT i.postId FROM TableBookmarkFolderItem i " +
            "WHERE i.userId = b.userId AND i.postId = b.postId)",
    )
    fun countUncategorizedByUserId(@Param("userId") userId: UUID): Long

    // 폴더에 추가 시 북마크가 아직 없으면 미분류로 생성 — 멱등
    @Modifying
    @Query(
        value = "INSERT INTO bookmarks (user_id, post_id, created_at) " +
            "VALUES (:userId, :postId, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun insertIgnoreConflict(@Param("userId") userId: UUID, @Param("postId") postId: UUID): Int
}
