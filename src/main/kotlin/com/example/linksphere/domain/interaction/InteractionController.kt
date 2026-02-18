package com.example.linksphere.domain.interaction

import com.example.linksphere.global.common.ApiResponse
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

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
            false // Unliked
        } else {
            reactionRepository.save(
                    TableReaction(
                            userId = userId,
                            targetId = targetId,
                            targetType = targetType,
                            reactionType = ReactionType.LIKE
                    )
            )
            true // Liked
        }
    }

    @Transactional
    fun toggleBookmark(postId: UUID, userId: UUID): Boolean {
        val exists = bookmarkRepository.existsByUserIdAndPostId(userId, postId)
        return if (exists) {
            bookmarkRepository.deleteByUserIdAndPostId(userId, postId)
            false // Unbookmarked
        } else {
            bookmarkRepository.save(TableBookmark(userId, postId))
            true // Bookmarked
        }
    }
}

@RestController
class InteractionController(private val interactionService: InteractionService) {

    private val logger = org.slf4j.LoggerFactory.getLogger(InteractionController::class.java)

    private fun getUserIdFromAuthentication(
            authentication: org.springframework.security.core.Authentication?
    ): UUID? {
        if (authentication == null) return null
        return try {
            UUID.fromString(authentication.name)
        } catch (e: Exception) {
            logger.warn("Failed to parse UUID from auth name: ${authentication.name}")
            null
        }
    }

    @PostMapping("/post/{postId}/like")
    fun likePost(
            @PathVariable postId: UUID,
            authentication: org.springframework.security.core.Authentication?
    ): ApiResponse<Map<String, Boolean>> {
        val userId =
                getUserIdFromAuthentication(authentication)
                        ?: throw IllegalArgumentException("User not authenticated")
        val isLiked = interactionService.toggleLike(postId, TargetType.POST, userId)
        return ApiResponse(200, if (isLiked) "좋아요 성공" else "좋아요 취소 성공", mapOf("isLiked" to isLiked))
    }

    @PostMapping("/comment/{commentId}/like")
    fun likeComment(
            @PathVariable commentId: UUID,
            authentication: org.springframework.security.core.Authentication?
    ): ApiResponse<Map<String, Boolean>> {
        val userId =
                getUserIdFromAuthentication(authentication)
                        ?: throw IllegalArgumentException("User not authenticated")
        val isLiked = interactionService.toggleLike(commentId, TargetType.COMMENT, userId)
        return ApiResponse(
                200,
                if (isLiked) "댓글 좋아요 성공" else "댓글 좋아요 취소 성공",
                mapOf("isLiked" to isLiked)
        )
    }

    @PostMapping("/post/{postId}/bookmark")
    fun bookmarkPost(
            @PathVariable postId: UUID,
            authentication: org.springframework.security.core.Authentication?
    ): ApiResponse<Map<String, Boolean>> {
        val userId =
                getUserIdFromAuthentication(authentication)
                        ?: throw IllegalArgumentException("User not authenticated")
        val isBookmarked = interactionService.toggleBookmark(postId, userId)
        return ApiResponse(
                200,
                if (isBookmarked) "북마크 성공" else "북마크 취소 성공",
                mapOf("isBookmarked" to isBookmarked)
        )
    }
}
