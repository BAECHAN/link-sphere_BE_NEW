package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TargetType
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.post.PostRepository
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CommentService(
        private val commentRepository: CommentRepository,
        private val postRepository: PostRepository,
        private val memberRepository: MemberRepository,
        private val reactionRepository: ReactionRepository
) {

        @Transactional(readOnly = true)
        fun getComments(postId: UUID, currentUserId: UUID?): List<CommentResponse> {
                val comments = commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)

                val commentIds = comments.map { it.id }
                val likeCounts =
                        reactionRepository
                                .findAllByTargetIdInAndTargetType(commentIds, TargetType.COMMENT)
                                .groupBy { it.targetId }
                                .mapValues { it.value.size }

                val userLikes =
                        currentUserId?.let { uid ->
                                reactionRepository
                                        .findAllByUserIdAndTargetIdInAndTargetType(
                                                uid,
                                                commentIds,
                                                TargetType.COMMENT
                                        )
                                        .map { it.targetId }
                                        .toSet()
                        }
                                ?: emptySet()

                // Map to DTOs first, keep flat list
                val commentDTOs =
                        comments.map { comment ->
                                val author =
                                        comment.member?.let {
                                                CommentAuthor(
                                                        it.id!!,
                                                        it.nickname ?: "Unknown",
                                                        it.image
                                                )
                                        }
                                                ?: CommentAuthor(
                                                        UUID.randomUUID(),
                                                        "Unknown",
                                                        null
                                                ) // Should not happen with consistent DB

                                CommentResponse(
                                        id = comment.id,
                                        postId = comment.postId,
                                        userId = comment.userId,
                                        content =
                                                if (comment.isDeleted) "삭제된 댓글입니다."
                                                else comment.content,
                                        isDeleted = comment.isDeleted,
                                        createdAt = comment.createdAt,
                                        updatedAt = comment.updatedAt,
                                        author = author,
                                        isLiked = userLikes.contains(comment.id),
                                        likeCount = likeCounts[comment.id] ?: 0
                                )
                        }

                // Reconstruct hierarchy
                // 1. Separate roots and replies
                val roots =
                        commentDTOs
                                .filter {
                                        // We need to check parentId from original entity.
                                        // Optimization: We can store parentId in DTO or use a map.
                                        // Let's create a map: ID -> Entity (or ParentID)
                                        false // Placeholder, see below loop
                                }
                                .toMutableList()

                // Efficient O(N) reconstruction
                val dtosById = commentDTOs.associateBy { it.id }
                val rootComments = mutableListOf<CommentResponse>()
                val repliesMap = mutableMapOf<UUID, MutableList<CommentResponse>>()

                comments.forEach { entity ->
                        val dto = dtosById[entity.id]!!
                        val pId = entity.parentId
                        if (pId == null) {
                                rootComments.add(dto)
                        } else {
                                repliesMap.computeIfAbsent(pId) { mutableListOf() }.add(dto)
                        }
                }

                // Assign replies to parents
                rootComments.forEach { root -> root.replies = repliesMap[root.id] ?: emptyList() }

                return rootComments
        }

        @Transactional
        fun createComment(
                postId: UUID,
                userId: UUID,
                content: String,
                parentId: UUID? = null
        ): CommentResponse {
                val post =
                        postRepository.findByIdOrNull(postId)
                                ?: throw IllegalArgumentException("Post not found")
                val member =
                        memberRepository.findByIdOrNull(userId)
                                ?: throw IllegalArgumentException("User not found")

                // Depth Check
                if (parentId != null) {
                        val parentUser =
                                commentRepository.findByIdOrNull(parentId)
                                        ?: throw IllegalArgumentException(
                                                "Parent comment not found"
                                        )

                        if (parentUser.parentId != null) {
                                throw IllegalArgumentException(
                                        "Reply to reply is not allowed (Max Depth 1)"
                                )
                        }
                        if (parentUser.postId != postId) {
                                throw IllegalArgumentException(
                                        "Parent comment belongs to a different post"
                                )
                        }
                }

                val comment =
                        TableComment(
                                postId = postId,
                                userId = userId,
                                parentId = parentId,
                                content = content
                        )
                val saved = commentRepository.save(comment)

                // Return DTO
                val author = CommentAuthor(member.id!!, member.nickname ?: "Unknown", member.image)
                return CommentResponse(
                        id = saved.id,
                        postId = saved.postId,
                        userId = saved.userId,
                        content = saved.content,
                        isDeleted = saved.isDeleted,
                        createdAt = saved.createdAt,
                        updatedAt = saved.updatedAt,
                        author = author
                )
        }

        @Transactional
        fun createReply(parentId: UUID, userId: UUID, content: String): CommentResponse {
                val parent =
                        commentRepository.findByIdOrNull(parentId)
                                ?: throw IllegalArgumentException("Parent comment not found")

                // Depth Check (Max Depth 1)
                if (parent.parentId != null) {
                        throw IllegalArgumentException(
                                "Reply to reply is not allowed (Max Depth 1)"
                        )
                }

                val member =
                        memberRepository.findByIdOrNull(userId)
                                ?: throw IllegalArgumentException("User not found")

                val comment =
                        TableComment(
                                postId = parent.postId,
                                userId = userId,
                                parentId = parentId,
                                content = content
                        )
                val saved = commentRepository.save(comment)

                val author = CommentAuthor(member.id!!, member.nickname ?: "Unknown", member.image)
                return CommentResponse(
                        id = saved.id,
                        postId = saved.postId,
                        userId = saved.userId,
                        content = saved.content,
                        isDeleted = saved.isDeleted,
                        createdAt = saved.createdAt,
                        updatedAt = saved.updatedAt,
                        author = author
                )
        }

        @Transactional
        fun deleteComment(commentId: UUID, userId: UUID) {
                val comment =
                        commentRepository.findByIdOrNull(commentId)
                                ?: throw IllegalArgumentException("Comment not found")

                if (comment.userId != userId) {
                        throw IllegalAccessException("Not authorized to delete this comment")
                }

                val hasReplies = commentRepository.existsByParentId(commentId)
                if (hasReplies) {
                        comment.content = "삭제된 댓글입니다."
                        comment.isDeleted = true
                        commentRepository.save(comment)
                } else {
                        commentRepository.delete(comment)
                }
        }
}
