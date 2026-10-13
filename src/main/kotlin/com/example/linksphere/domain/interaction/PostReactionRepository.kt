package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface PostReactionRepository : JpaRepository<TablePostReaction, PostReactionId> {
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean

    // 일괄 DELETE — derived delete(엔티티 로드 후 remove)는 동시 요청이 먼저 지운 행을 다시 지우려다
    // StaleObjectStateException(500)을 낸다. 이 쿼리는 0행이어도 예외 없이 0을 반환한다.
    @Modifying
    @Query("DELETE FROM TablePostReaction r WHERE r.userId = :userId AND r.postId = :postId")
    fun deleteByUserIdAndPostId(@Param("userId") userId: UUID, @Param("postId") postId: UUID): Int

    // 중복 요청/동시 탭에도 안전한 멱등 insert
    @Modifying
    @Query(
        value = "INSERT INTO post_reactions (user_id, post_id, created_at) " +
            "VALUES (:userId, :postId, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
        nativeQuery = true,
    )
    fun insertIgnoreConflict(@Param("userId") userId: UUID, @Param("postId") postId: UUID): Int

    // 회원탈퇴 시(AccountDeletionService) 그 회원의 좋아요를 전부 지운다.
    @Modifying
    @Query("DELETE FROM TablePostReaction r WHERE r.userId = :userId")
    fun deleteByUserId(@Param("userId") userId: UUID): Int
    fun countByPostId(postId: UUID): Long
    fun findAllByPostIdIn(postIds: List<UUID>): List<TablePostReaction>
    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TablePostReaction>
}
