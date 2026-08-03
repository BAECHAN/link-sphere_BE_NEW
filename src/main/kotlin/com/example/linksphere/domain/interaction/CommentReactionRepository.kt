package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface CommentReactionRepository : JpaRepository<TableCommentReaction, CommentReactionId> {
    fun existsByUserIdAndCommentId(userId: UUID, commentId: UUID): Boolean
    fun deleteByUserIdAndCommentId(userId: UUID, commentId: UUID)
    fun findAllByCommentIdIn(commentIds: List<UUID>): List<TableCommentReaction>
    fun findAllByUserIdAndCommentIdIn(userId: UUID, commentIds: List<UUID>): List<TableCommentReaction>

    // 댓글 삭제(톰스톤 처리) 시 사용 — comments row가 살아남아 FK 캐스케이드가 발동하지 않는 경로용
    @Modifying
    @Query("DELETE FROM TableCommentReaction r WHERE r.commentId = :commentId")
    fun deleteByCommentId(@Param("commentId") commentId: UUID): Int
}
