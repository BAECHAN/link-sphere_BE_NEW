package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.global.exception.PostNotFoundException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class InteractionService(
    private val reactionRepository: ReactionRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val postRepository: PostRepository,
    private val commentRepository: CommentRepository,
) {
    @Transactional
    fun toggleLike(targetId: UUID, targetType: TargetType, userId: UUID): Boolean {
        when (targetType) {
            TargetType.POST -> assertPostVisible(targetId, userId)
            TargetType.COMMENT -> {
                val comment =
                    commentRepository.findByIdOrNull(targetId)
                        ?: throw IllegalArgumentException("Comment not found: $targetId")
                // 비공개 글에 달린 댓글도 글 소유자 외에는 좋아요를 달 수 없어야 한다.
                assertPostVisible(comment.postId, userId)
            }
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
                    reactionType = ReactionType.LIKE,
                ),
            )
            true
        }
    }

    @Transactional
    fun toggleBookmark(postId: UUID, userId: UUID): Boolean {
        assertPostVisible(postId, userId)

        val exists = bookmarkRepository.existsByUserIdAndPostId(userId, postId)
        return if (exists) {
            bookmarkRepository.deleteByUserIdAndPostId(userId, postId)
            false
        } else {
            bookmarkRepository.save(TableBookmark(userId, postId))
            true
        }
    }

    /**
     * 글이 존재하고, 비공개라면 소유자만 접근 가능함을 확인한다.
     * PostService.getPostById와 동일한 기준: 존재 여부를 알려주지 않도록 403이 아닌 404로 던진다.
     */
    private fun assertPostVisible(postId: UUID, userId: UUID) {
        val post = postRepository.findByIdOrNull(postId) ?: throw PostNotFoundException(postId)
        if (post.isPrivate && post.userId != userId) throw PostNotFoundException(postId)
    }
}
