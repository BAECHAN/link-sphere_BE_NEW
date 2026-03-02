package com.example.linksphere.domain.interaction

import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InteractionService(
        private val reactionRepository: ReactionRepository,
        private val bookmarkRepository: BookmarkRepository
) {
    @Transactional
    fun toggleLike(targetId: UUID, targetType: TargetType, userId: UUID): Boolean {
        val existing =
                reactionRepository.findByTargetIdAndTargetTypeAndUserId(
                        targetId,
                        targetType,
                        userId
                )
        return if (existing != null) {
            reactionRepository.delete(existing)
            false
        } else {
            reactionRepository.save(
                    TableReaction(
                            userId = userId,
                            targetId = targetId,
                            targetType = targetType,
                            reactionType = ReactionType.LIKE
                    )
            )
            true
        }
    }

    @Transactional
    fun toggleBookmark(postId: UUID, userId: UUID): Boolean {
        val exists = bookmarkRepository.existsByUserIdAndPostId(userId, postId)
        return if (exists) {
            bookmarkRepository.deleteByUserIdAndPostId(userId, postId)
            false
        } else {
            bookmarkRepository.save(TableBookmark(userId, postId))
            true
        }
    }
}
