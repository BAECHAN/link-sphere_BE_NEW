package com.example.linksphere.domain.interaction

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PostReactionRepository : JpaRepository<TablePostReaction, PostReactionId> {
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean
    fun deleteByUserIdAndPostId(userId: UUID, postId: UUID)

    // 회원탈퇴 시(AccountDeletionService) 그 회원의 좋아요를 전부 지운다.
    fun deleteByUserId(userId: UUID)
    fun countByPostId(postId: UUID): Long
    fun findAllByPostIdIn(postIds: List<UUID>): List<TablePostReaction>
    fun findAllByUserIdAndPostIdIn(userId: UUID, postIds: List<UUID>): List<TablePostReaction>
}
