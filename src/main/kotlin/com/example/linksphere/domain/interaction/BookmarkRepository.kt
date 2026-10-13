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

    // 일괄 DELETE — PostReactionRepository.deleteByUserIdAndPostId 와 같은 이유(동시 요청에도 예외 없음)
    @Modifying
    @Query("DELETE FROM TableBookmark b WHERE b.userId = :userId AND b.postId = :postId")
    fun deleteByUserIdAndPostId(@Param("userId") userId: UUID, @Param("postId") postId: UUID): Int

    // 다중 선택 일괄 삭제(BookmarkFolderService.batchDeleteBookmarks) — 실제로 지운 개수를 돌려준다
    @Modifying
    @Query("DELETE FROM TableBookmark b WHERE b.userId = :userId AND b.postId IN :postIds")
    fun deleteByUserIdAndPostIdIn(@Param("userId") userId: UUID, @Param("postIds") postIds: List<UUID>): Int

    // 회원탈퇴 시(AccountDeletionService) 그 회원의 북마크를 전부 지운다.
    @Modifying
    @Query("DELETE FROM TableBookmark b WHERE b.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int
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
