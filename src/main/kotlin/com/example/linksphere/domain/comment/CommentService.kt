package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TargetType
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.UrlMetadataExtractor
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.infra.fcm.FcmNotificationService
import java.util.UUID
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile

private const val DELETED_COMMENT_CONTENT = "삭제된 댓글입니다."

private val URL_REGEX = Regex("""https?://\S+""")

@Service
class CommentService(
        private val commentRepository: CommentRepository,
        private val postRepository: PostRepository,
        private val memberRepository: MemberRepository,
        private val reactionRepository: ReactionRepository,
        private val supabaseStorageService: SupabaseStorageService,
        private val fcmNotificationService: FcmNotificationService,
        private val urlMetadataExtractor: UrlMetadataExtractor,
) {

        @Transactional(readOnly = true)
        fun getComments(postId: UUID, currentUserId: UUID?): List<CommentResponse> {
                val comments = commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)

                val commentIds = comments.map { it.id }
                val likeCounts =
                        reactionRepository
                                .findAllByTargetIdInAndTargetType(commentIds, TargetType.COMMENT)
                                .groupingBy { it.targetId }
                                .eachCount()

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
                                                if (comment.isDeleted) DELETED_COMMENT_CONTENT
                                                else comment.content,
                                        isDeleted = comment.isDeleted,
                                        createdAt = comment.createdAt,
                                        updatedAt = comment.updatedAt,
                                        author = author,
                                        isLiked = userLikes.contains(comment.id),
                                        likeCount = likeCounts[comment.id] ?: 0,
                                        linkMetadata = if (comment.isDeleted) null
                                                else comment.linkUrl?.let {
                                                        LinkMetadata(
                                                                url = it,
                                                                title = comment.linkTitle ?: it,
                                                                description = comment.linkDescription,
                                                                ogImage = comment.linkOgImage,
                                                        )
                                                },
                                )
                        }

                // Reconstruct hierarchy
                // 1. Separate roots and replies

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
                content: String?,
                images: List<MultipartFile>?,
                parentId: UUID? = null
        ): CommentResponse {
                if (content.isNullOrBlank() && images?.isEmpty() ?: true) {
                        throw IllegalArgumentException("Content or image must be provided")
                }

                // Depth Check (fail fast, before image upload)
                if (parentId != null) {
                        val parentComment =
                                commentRepository.findByIdOrNull(parentId)
                                        ?: throw IllegalArgumentException(
                                                "Parent comment not found"
                                        )
                        if (parentComment.parentId != null) {
                                throw IllegalArgumentException(
                                        "Reply to reply is not allowed (Max Depth 1)"
                                )
                        }
                        if (parentComment.postId != postId) {
                                throw IllegalArgumentException(
                                        "Parent comment belongs to a different post"
                                )
                        }
                }

                val post =
                        postRepository.findByIdOrNull(postId)
                                ?: throw IllegalArgumentException("Post not found")
                val member =
                        memberRepository.findByIdOrNull(userId)
                                ?: throw IllegalArgumentException("User not found")

                val finalContent = buildFinalContent(content, images)
                val linkMeta = extractFirstLinkMetadata(finalContent)

                val comment =
                        TableComment(
                                postId = postId,
                                userId = userId,
                                parentId = parentId,
                                content = finalContent,
                                linkUrl = linkMeta?.first,
                                linkTitle = linkMeta?.second?.title,
                                linkDescription = linkMeta?.second?.description,
                                linkOgImage = linkMeta?.second?.ogImage,
                        )
                val saved = commentRepository.save(comment)

                // 내 포스트에 타인이 댓글을 달면 알림 (루트 댓글만, 자기 자신 제외)
                if (parentId == null && post.userId != userId) {
                        fcmNotificationService.sendCommentNotification(
                                postAuthorId = post.userId,
                                commenterNickname = member.nickname ?: "누군가",
                                commentContent = finalContent.take(50),
                                postId = postId,
                                commentId = saved.id
                        )
                }

                return toCommentResponse(saved, member)
        }

        @Transactional
        fun createReply(
                parentId: UUID,
                userId: UUID,
                content: String?,
                images: List<MultipartFile>?
        ): CommentResponse {
                if (content.isNullOrBlank() && images?.isEmpty() ?: true) {
                        throw IllegalArgumentException("Content or image must be provided")
                }

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

                val finalContent = buildFinalContent(content, images)
                val linkMeta = extractFirstLinkMetadata(finalContent)

                val comment =
                        TableComment(
                                postId = parent.postId,
                                userId = userId,
                                parentId = parentId,
                                content = finalContent,
                                linkUrl = linkMeta?.first,
                                linkTitle = linkMeta?.second?.title,
                                linkDescription = linkMeta?.second?.description,
                                linkOgImage = linkMeta?.second?.ogImage,
                        )
                val saved = commentRepository.save(comment)

                // 내 댓글에 타인이 답글을 달면 알림 (자기 자신 제외)
                if (parent.userId != userId) {
                        fcmNotificationService.sendReplyNotification(
                                parentCommentAuthorId = parent.userId,
                                replierNickname = member.nickname ?: "누군가",
                                replyContent = finalContent.take(50),
                                postId = saved.postId,
                                commentId = saved.id
                        )
                }

                return toCommentResponse(saved, member)
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
                        comment.content = DELETED_COMMENT_CONTENT
                        comment.isDeleted = true
                        commentRepository.save(comment)
                } else {
                        commentRepository.delete(comment)
                }
        }

        @Transactional
        fun updateComment(
                commentId: UUID,
                userId: UUID,
                content: String?,
                images: List<MultipartFile>?
        ): CommentResponse {
                if (content.isNullOrBlank() && images?.isEmpty() ?: true) {
                        throw IllegalArgumentException("Content or image must be provided")
                }

                val comment =
                        commentRepository.findByIdOrNull(commentId)
                                ?: throw IllegalArgumentException("Comment not found")

                if (comment.userId != userId) {
                        throw IllegalAccessException("Not authorized to update this comment")
                }

                if (comment.isDeleted) {
                        throw IllegalStateException("Cannot update a deleted comment")
                }

                val finalContent = buildFinalContent(content, images)
                val linkMeta = extractFirstLinkMetadata(finalContent)

                comment.content = finalContent
                comment.linkUrl = linkMeta?.first
                comment.linkTitle = linkMeta?.second?.title
                comment.linkDescription = linkMeta?.second?.description
                comment.linkOgImage = linkMeta?.second?.ogImage
                val updated = commentRepository.save(comment)

                val member =
                        memberRepository.findByIdOrNull(userId)
                                ?: throw IllegalArgumentException("User not found")

                return toCommentResponse(updated, member)
        }

        private fun buildFinalContent(content: String?, images: List<MultipartFile>?): String {
                val text = content.orEmpty()
                val urls = images
                        ?.filter { !it.isEmpty }
                        ?.map { supabaseStorageService.uploadFile(it) }
                        ?: emptyList()
                return when {
                        urls.isEmpty() -> text
                        text.isNotBlank() -> "$text\n\n${urls.joinToString("\n")}"
                        else -> urls.joinToString("\n")
                }
        }

        /** content에서 첫 번째 URL을 찾아 메타데이터를 추출. URL이 없으면 null 반환 */
        private fun extractFirstLinkMetadata(content: String): Pair<String, com.example.linksphere.domain.post.UrlMetadata>? {
                val url = URL_REGEX.find(content)?.value ?: return null
                val meta = urlMetadataExtractor.extract(url)
                return url to meta
        }

        private fun toCommentResponse(comment: TableComment, member: TableMember) =
                CommentResponse(
                        id = comment.id,
                        postId = comment.postId,
                        userId = comment.userId,
                        content = comment.content,
                        isDeleted = comment.isDeleted,
                        createdAt = comment.createdAt,
                        updatedAt = comment.updatedAt,
                        author = CommentAuthor(member.id!!, member.nickname ?: "Unknown", member.image),
                        linkMetadata = comment.linkUrl?.let {
                                LinkMetadata(
                                        url = it,
                                        title = comment.linkTitle ?: it,
                                        description = comment.linkDescription,
                                        ogImage = comment.linkOgImage,
                                )
                        },
                )
}
