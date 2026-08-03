package com.example.linksphere.domain.interaction

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

data class CommentReactionId(val userId: UUID = UUID(0, 0), val commentId: UUID = UUID(0, 0)) : Serializable

@Entity
@Table(
    name = "comment_reactions",
    indexes = [
        Index(name = "idx_comment_reactions_comment_user", columnList = "comment_id, user_id"),
    ],
)
@IdClass(CommentReactionId::class)
class TableCommentReaction(
    @Id @Column(name = "user_id", nullable = false) val userId: UUID,
    @Id @Column(name = "comment_id", nullable = false) val commentId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
)
