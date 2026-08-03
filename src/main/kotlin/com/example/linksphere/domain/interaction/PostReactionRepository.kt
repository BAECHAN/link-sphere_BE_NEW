package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PostReactionRepository : JpaRepository<TablePostReaction, PostReactionId> {
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean
    fun deleteByUserIdAndPostId(userId: UUID, postId: UUID)
    fun countByPostId(postId: UUID): Long
    fun findAllByPostIdIn(postIds: List<UUID>): List<TablePostReaction>
    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TablePostReaction>
}
