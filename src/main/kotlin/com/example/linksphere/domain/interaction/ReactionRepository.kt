package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReactionRepository : JpaRepository<TableReaction, ReactionId> {
    fun findByTargetIdAndTargetTypeAndUserId(
        targetId: UUID,
        targetType: TargetType,
        userId: UUID,
    ): TableReaction?
    fun deleteByTargetIdAndTargetTypeAndUserId(
        targetId: UUID,
        targetType: TargetType,
        userId: UUID,
    )
    fun existsByTargetIdAndTargetTypeAndUserId(
        targetId: UUID,
        targetType: TargetType,
        userId: UUID,
    ): Boolean
    fun countByTargetIdAndTargetType(targetId: UUID, targetType: TargetType): Long
    fun findAllByTargetIdInAndTargetType(
        targetIds: List<UUID>,
        targetType: TargetType,
    ): List<TableReaction>
    fun findAllByUserIdAndTargetIdInAndTargetType(
        userId: UUID,
        targetIds: List<UUID>,
        targetType: TargetType,
    ): List<TableReaction>
}
