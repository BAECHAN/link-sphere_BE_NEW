package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface FolderBookmarkCount {
    val folderId: UUID
    val count: Long
}

interface BookmarkFolderItemRepository : JpaRepository<TableBookmarkFolderItem, BookmarkFolderItemId> {

    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TableBookmarkFolderItem>

    @Query("SELECT i.folderId FROM TableBookmarkFolderItem i WHERE i.userId = :userId AND i.postId = :postId")
    fun findFolderIdsByUserIdAndPostId(@Param("userId") userId: UUID, @Param("postId") postId: UUID): List<UUID>

    fun countByFolderId(folderId: UUID): Long

    // 폴더별 개수 — 폴더 수와 무관하게 쿼리 1회 (getFolders 의 N+1 루프 제거용)
    @Query(
        "SELECT i.folderId as folderId, COUNT(i) as count FROM TableBookmarkFolderItem i " +
            "WHERE i.userId = :userId GROUP BY i.folderId",
    )
    fun countByUserIdGroupByFolderId(@Param("userId") userId: UUID): List<FolderBookmarkCount>

    // 중복 요청/동시 탭에도 안전한 멱등 insert
    @Modifying
    @Query(
        value = "INSERT INTO bookmark_folder_items (user_id, post_id, folder_id, created_at) " +
            "VALUES (:userId, :postId, :folderId, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun insertIgnoreConflict(
        @Param("userId") userId: UUID,
        @Param("postId") postId: UUID,
        @Param("folderId") folderId: UUID,
    ): Int

    @Modifying
    @Query(
        "DELETE FROM TableBookmarkFolderItem i " +
            "WHERE i.userId = :userId AND i.postId = :postId AND i.folderId = :folderId",
    )
    fun deleteByUserIdAndPostIdAndFolderId(
        @Param("userId") userId: UUID,
        @Param("postId") postId: UUID,
        @Param("folderId") folderId: UUID,
    ): Int

    @Modifying
    @Query("DELETE FROM TableBookmarkFolderItem i WHERE i.userId = :userId AND i.postId = :postId")
    fun deleteByUserIdAndPostId(@Param("userId") userId: UUID, @Param("postId") postId: UUID): Int

    @Modifying
    @Query("DELETE FROM TableBookmarkFolderItem i WHERE i.userId = :userId AND i.postId IN :postIds")
    fun deleteByUserIdAndPostIdIn(@Param("userId") userId: UUID, @Param("postIds") postIds: List<UUID>): Int

    @Modifying
    @Query("DELETE FROM TableBookmarkFolderItem i WHERE i.folderId = :folderId")
    fun deleteByFolderId(@Param("folderId") folderId: UUID): Int
}
