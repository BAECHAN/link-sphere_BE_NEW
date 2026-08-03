package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.UrlMetadataExtractor
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.PostNotFoundException
import com.example.linksphere.infra.fcm.FcmNotificationService
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val DELETED_COMMENT_CONTENT = "삭제된 댓글입니다."

private val URL_REGEX = Regex("""https?://\S+""")

@Service
class CommentService(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val memberRepository: MemberRepository,
    private val commentReactionRepository: CommentReactionRepository,
    private val fcmNotificationService: FcmNotificationService,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val supabaseStorageService: SupabaseStorageService,
) {

    @Transactional(readOnly = true)
    fun getComments(postId: UUID, currentUserId: UUID?): List<CommentResponse> {
        val post = postRepository.findById(postId).orElseThrow { PostNotFoundException(postId) }
        // 비공개 글의 댓글도 상세 조회와 동일한 기준으로 소유자에게만 보여준다.
        if (post.isPrivate && post.userId != currentUserId) throw PostNotFoundException(postId)

        val comments = commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)

        val commentIds = comments.map { it.id }
        val likeCounts =
            commentReactionRepository
                .findAllByCommentIdIn(commentIds)
                .groupingBy { it.commentId }
                .eachCount()

        val userLikes =
            currentUserId?.let { uid ->
                commentReactionRepository
                    .findAllByUserIdAndCommentIdIn(uid, commentIds)
                    .map { it.commentId }
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
                            it.image,
                        )
                    }
                        ?: CommentAuthor(
                            UUID.randomUUID(),
                            "Unknown",
                            null,
                        ) // Should not happen with consistent DB

                CommentResponse(
                    id = comment.id,
                    postId = comment.postId,
                    userId = comment.userId,
                    content =
                    if (comment.isDeleted) {
                        DELETED_COMMENT_CONTENT
                    } else {
                        comment.content
                    },
                    isDeleted = comment.isDeleted,
                    createdAt = comment.createdAt,
                    updatedAt = comment.updatedAt,
                    author = author,
                    // 삭제된(톰스톤) 댓글은 좋아요도 함께 지워지지만, 삭제와 좋아요가 동시에 일어나는
                    // 경쟁 상황의 잔여값이 표현 계층까지 새지 않도록 여기서도 명시적으로 0/false로 고정한다.
                    isLiked = !comment.isDeleted && userLikes.contains(comment.id),
                    likeCount = if (comment.isDeleted) 0 else likeCounts[comment.id] ?: 0,
                    linkMetadata = if (comment.isDeleted) {
                        null
                    } else {
                        comment.linkUrl?.let {
                            LinkMetadata(
                                url = it,
                                title = comment.linkTitle ?: it,
                                description = comment.linkDescription,
                                ogImage = comment.linkOgImage,
                            )
                        }
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
        images: List<String>?,
        parentId: UUID? = null,
    ): CommentResponse {
        if (content.isNullOrBlank() && images?.isEmpty() ?: true) {
            throw IllegalArgumentException("Content or image must be provided")
        }

        // Depth Check (fail fast, before image upload)
        if (parentId != null) {
            val parentComment =
                commentRepository.findByIdOrNull(parentId)
                    ?: throw IllegalArgumentException(
                        "Parent comment not found",
                    )
            if (parentComment.parentId != null) {
                throw IllegalArgumentException(
                    "Reply to reply is not allowed (Max Depth 1)",
                )
            }
            if (parentComment.postId != postId) {
                throw IllegalArgumentException(
                    "Parent comment belongs to a different post",
                )
            }
        }

        val post =
            postRepository.findByIdOrNull(postId)
                ?: throw PostNotFoundException(postId)
        // 조회·댓글 목록과 동일한 기준: 읽을 수 없는 비공개 글에는 댓글도 달 수 없다.
        if (post.isPrivate && post.userId != userId) throw PostNotFoundException(postId)
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
                commentId = saved.id,
            )
        }

        return toCommentResponse(saved, member)
    }

    @Transactional
    fun createReply(
        parentId: UUID,
        userId: UUID,
        content: String?,
        images: List<String>?,
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
                "Reply to reply is not allowed (Max Depth 1)",
            )
        }

        val post =
            postRepository.findByIdOrNull(parent.postId)
                ?: throw PostNotFoundException(parent.postId)
        // 조회·댓글 목록과 동일한 기준: 읽을 수 없는 비공개 글에는 답글도 달 수 없다.
        if (post.isPrivate && post.userId != userId) throw PostNotFoundException(parent.postId)

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
                commentId = saved.id,
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

        supabaseStorageService.deleteObjectsByPublicUrls(extractManagedImageUrls(comment.content))
        // 톰스톤은 comments row가 살아남아 FK 캐스케이드가 발동하지 않으므로 명시 삭제가 유일한 수단이다.
        // 하드 삭제 경로에서는 comment_reactions FK ON DELETE CASCADE 가 백스톱으로 남는다.
        commentReactionRepository.deleteByCommentId(commentId)

        val hasReplies = commentRepository.existsByParentId(commentId)
        if (hasReplies) {
            comment.content = DELETED_COMMENT_CONTENT
            comment.isDeleted = true
            commentRepository.save(comment)
        } else {
            commentRepository.delete(comment)
        }
    }

    /**
     * 게시글 삭제 시 호출된다. comments.post_id FK가 ON DELETE CASCADE라 DB에서는
     * 게시글과 함께 자동으로 지워지지만, 그 댓글들에 딸린 스토리지 이미지는 별도로
     * 정리해야 하므로 게시글이 실제로 삭제되기 전에 댓글 내용을 읽어 URL을 추출해둔다.
     */
    @Transactional(readOnly = true)
    fun deleteImagesForPost(postId: UUID) {
        val comments = commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)
        val imageUrls = comments.flatMap { extractManagedImageUrls(it.content) }
        supabaseStorageService.deleteObjectsByPublicUrls(imageUrls)
    }

    private fun extractManagedImageUrls(content: String): List<String> = URL_REGEX.findAll(content)
        .map { it.value }
        .filter { supabaseStorageService.isManagedUrl(it) }
        .toList()

    @Transactional
    fun updateComment(
        commentId: UUID,
        userId: UUID,
        content: String?,
        images: List<String>?,
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

    private fun buildFinalContent(content: String?, images: List<String>?): String {
        val text = content.orEmpty()
        val urls = images?.filter { it.isNotBlank() } ?: emptyList()
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

    private fun toCommentResponse(comment: TableComment, member: TableMember) = CommentResponse(
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
