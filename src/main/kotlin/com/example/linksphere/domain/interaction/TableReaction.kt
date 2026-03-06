package com.example.linksphere.domain.interaction

import jakarta.persistence.*
import java.io.Serializable
import java.time.LocalDateTime
import java.util.UUID

enum class ReactionType {
    LIKE,
    LOVE,
    CLAP,
    IDEA,
    THINKING
}

enum class TargetType {
    POST,
    COMMENT
}

data class ReactionId(
        val userId: UUID = UUID(0, 0),
        val targetId: UUID = UUID(0, 0),
        val targetType: TargetType = TargetType.POST
) : Serializable

@Entity
@Table(
        name = "reactions",
        indexes =
                [
                        Index(name = "idx_reaction_target", columnList = "target_id, target_type"),
                        Index(
                                name = "idx_reaction_user_target",
                                columnList = "user_id, target_id, target_type",
                                unique = true
                        )]
)
@IdClass(ReactionId::class)
class TableReaction(
        @Id @Column(name = "user_id", nullable = false) val userId: UUID,
        @Id @Column(name = "target_id", nullable = false) val targetId: UUID,
        @Id
        @Enumerated(EnumType.STRING)
        @Column(name = "target_type", nullable = false)
        val targetType: TargetType,
        @Enumerated(EnumType.STRING)
        @Column(name = "reaction_type", nullable = false)
        val reactionType: ReactionType,
        @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now()
)
