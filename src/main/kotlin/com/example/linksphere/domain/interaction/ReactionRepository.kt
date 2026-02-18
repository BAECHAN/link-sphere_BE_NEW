package com.example.linksphere.domain.interaction

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ReactionRepository : JpaRepository<TableReaction, ReactionId> {
    fun countByTargetIdAndTargetType(targetId: UUID, targetType: TargetType): Int
    fun existsByUserIdAndTargetIdAndTargetType(
            userId: UUID,
            targetId: UUID,
            targetType: TargetType
    ): Boolean
}
