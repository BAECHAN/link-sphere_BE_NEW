package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

private const val DELETED_COMMENT_CONTENT = "삭제된 댓글입니다."

private val URL_REGEX = Regex("""https?://\S+""")

@Service
class CommentService(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val memberRepository: MemberRepository,
    private val commentReactionRepository: CommentReactionRepository,
    private val supabaseStorageService: SupabaseStorageService,
    private val eventPublisher: ApplicationEventPublisher,
) {

    companion object {
        private const val MAX_COMMENT_IMAGES = 5
    }

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
        if ((images?.size ?: 0) > MAX_COMMENT_IMAGES) {
            throw InvalidInputException("Comment images cannot exceed $MAX_COMMENT_IMAGES")
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

        val comment =
            TableComment(
                postId = postId,
                userId = userId,
                parentId = parentId,
                content = finalContent,
                linkUrl = extractFirstNonImageUrl(finalContent),
            )
        val saved = commentRepository.save(comment)

        // 알림·링크 프리뷰는 요청 경로 밖(커밋 후 별도 Lambda)에서 처리한다 - CommentPostProcessService 참고.
        // 알림 조건(루트 댓글만, 자기 자신 제외)은 지금 판정해 이벤트에 실어 보낸다.
        eventPublisher.publishEvent(
            CommentPostProcessEvent(commentId = saved.id, notify = parentId == null && post.userId != userId),
        )

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
        if ((images?.size ?: 0) > MAX_COMMENT_IMAGES) {
            throw InvalidInputException("Comment images cannot exceed $MAX_COMMENT_IMAGES")
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

        val comment =
            TableComment(
                postId = parent.postId,
                userId = userId,
                parentId = parentId,
                content = finalContent,
                linkUrl = extractFirstNonImageUrl(finalContent),
            )
        val saved = commentRepository.save(comment)

        // 알림·링크 프리뷰는 요청 경로 밖(커밋 후 별도 Lambda)에서 처리한다 - CommentPostProcessService 참고.
        // 알림 조건(자기 자신 제외)은 지금 판정해 이벤트에 실어 보낸다.
        eventPublisher.publishEvent(
            CommentPostProcessEvent(commentId = saved.id, notify = parent.userId != userId),
        )

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

        val imageUrls = extractManagedImageUrls(comment.content)

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

        // 이미지 삭제는 반드시 커밋 이후에 실행한다 - updateComment와 동일한 이유: 위 DB 작업이
        // 실패해 트랜잭션이 롤백되면 댓글은 DB에 그대로 남았는데 파일만 사라진 상태가 된다.
        if (imageUrls.isNotEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        supabaseStorageService.deleteObjectsByPublicUrls(imageUrls)
                    }
                },
            )
        }
    }

    /**
     * 게시글 삭제 시 호출된다. comments.post_id FK가 ON DELETE CASCADE라 DB에서는
     * 게시글과 함께 자동으로 지워지지만, 그 댓글들에 딸린 스토리지 이미지는 별도로
     * 정리해야 하므로 게시글이 실제로 삭제되기 전에 댓글 내용을 읽어 URL을 추출해둔다.
     * 실제 스토리지 삭제는 deleteComment/updateComment와 동일한 이유로 커밋 이후로 미룬다 -
     * 호출부(PostService.deletePost)의 postRepository.delete가 실패해 롤백되면 게시글·댓글은
     * DB에 그대로 남았는데 파일만 사라진 상태가 되는 걸 막는다.
     */
    @Transactional(readOnly = true)
    fun deleteImagesForPost(postId: UUID) {
        val contents = commentRepository.findAllContentByPostId(postId)
        val imageUrls = contents.flatMap { extractManagedImageUrls(it) }
        if (imageUrls.isNotEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        supabaseStorageService.deleteObjectsByPublicUrls(imageUrls)
                    }
                },
            )
        }
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
        if ((images?.size ?: 0) > MAX_COMMENT_IMAGES) {
            throw InvalidInputException("Comment images cannot exceed $MAX_COMMENT_IMAGES")
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

        val previousImageUrls = extractManagedImageUrls(comment.content)

        val finalContent = buildFinalContent(content, images)

        comment.content = finalContent
        comment.linkUrl = extractFirstNonImageUrl(finalContent)
        // 링크가 바뀌었을 수 있으니 이전 프리뷰는 비우고, 후처리 job이 다시 채운다.
        comment.linkTitle = null
        comment.linkDescription = null
        comment.linkOgImage = null
        val updated = commentRepository.save(comment)

        // 이미지 삭제는 반드시 커밋 이후에 실행한다 - 아래 memberRepository 조회가 실패하면
        // 트랜잭션이 롤백되는데, 여기서 바로 지우면 DB엔 옛 URL이 남고 파일은 이미 사라진
        // 상태가 되어 깨진 이미지가 된다. 본문 텍스트에 직접 써둔 URL은 새 content에도
        // 남으므로 차집합에서 빠져 보존된다.
        val removedImageUrls = previousImageUrls - extractManagedImageUrls(finalContent).toSet()
        if (removedImageUrls.isNotEmpty()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        supabaseStorageService.deleteObjectsByPublicUrls(removedImageUrls)
                    }
                },
            )
        }

        // 알림은 보내지 않는다(수정은 알림 대상 아님) - 링크 프리뷰만 커밋 후 별도 Lambda에서 갱신.
        eventPublisher.publishEvent(CommentPostProcessEvent(commentId = updated.id, notify = false))

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

    /**
     * content에서 첫 번째 "이미지 아닌" URL을 찾는다. buildFinalContent가 업로드된 이미지 URL을
     * content 뒤에 이어붙이므로, 이걸 걸러내지 않으면 텍스트에 링크가 없는 이미지 댓글이
     * 자기 자신의 Supabase 이미지 URL을 크롤링 대상으로 잡는다. 메타데이터 크롤링 자체는
     * 커밋 후 별도 Lambda(CommentPostProcessService)에서 수행한다.
     */
    private fun extractFirstNonImageUrl(content: String): String? = URL_REGEX.findAll(content)
        .map { it.value }
        .firstOrNull { !supabaseStorageService.isManagedUrl(it) }

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
