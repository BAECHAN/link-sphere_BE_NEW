package com.example.linksphere.domain.interaction

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

data class PostReactionId(val userId: UUID = UUID(0, 0), val postId: UUID = UUID(0, 0)) : Serializable

@Entity
@Table(
    name = "post_reactions",
    indexes = [
        Index(name = "idx_post_reactions_post_user", columnList = "post_id, user_id"),
    ],
)
@IdClass(PostReactionId::class)
class TablePostReaction(
    @Id @Column(name = "user_id", nullable = false) val userId: UUID,
    @Id @Column(name = "post_id", nullable = false) val postId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: LocalDateTime = LocalDateTime.now(),
)
