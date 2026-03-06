package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.post.PostRepository
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class InteractionService(
        private val reactionRepository: ReactionRepository,
        private val bookmarkRepository: BookmarkRepository,
        private val postRepository: PostRepository,
        private val commentRepository: CommentRepository
) {
    @Transactional
    fun toggleLike(targetId: UUID, targetType: TargetType, userId: UUID): Boolean {
        when (targetType) {
            TargetType.POST ->
                    if (!postRepository.existsById(targetId))
                            throw IllegalArgumentException("Post not found: $targetId")
            TargetType.COMMENT ->
                    if (!commentRepository.existsById(targetId))
                            throw IllegalArgumentException("Comment not found: $targetId")
        }

        val exists = reactionRepository.existsByTargetIdAndTargetTypeAndUserId(targetId, targetType, userId)
        return if (exists) {
            reactionRepository.deleteByTargetIdAndTargetTypeAndUserId(targetId, targetType, userId)
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
        if (!postRepository.existsById(postId)) throw IllegalArgumentException("Post not found: $postId")

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
