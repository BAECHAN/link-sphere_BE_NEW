package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.member.EmailVerificationGate
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.PostVisibility
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.CursorCodec
import com.example.linksphere.global.common.CursorKeyType
import com.example.linksphere.global.common.Paging
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.common.TransactionCallbacks
import com.example.linksphere.global.exception.CommentDeletedException
import com.example.linksphere.global.exception.CommentNotFoundException
import com.example.linksphere.global.exception.DuplicateCommentException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Limit
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

private const val DELETED_COMMENT_CONTENT = "삭제된 댓글입니다."

// 내 댓글 목록은 정렬이 하나뿐이다. cursor에 담는 키는 findMyCommentsAfter의 인자 순서와 맞춘다
private const val MY_COMMENTS_SORT = "LATEST"
private val MY_COMMENTS_KEYS = listOf(CursorKeyType.TIME, CursorKeyType.ID)

// 회원탈퇴 신청(유예 중) 또는 익명화 완료로 publicNickname이 null인 경우의 표시명
// (TableMember.isWithdrawn 참고) - 유예 중에도 퍼지가 끝난 것과 동일하게 보인다. member
// 연관관계 자체가 null인 경우(정합성 깨진 데이터, "Should not happen with consistent DB")에도
// 같은 문구를 쓴다 - 둘 다 "이 댓글의 작성자 정보를 보여줄 수 없다"는 같은 상황이라 사용자에게
// 굳이 구분해 보일 이유가 없다.
private const val DELETED_MEMBER_NICKNAME = "탈퇴한 사용자"

private val URL_REGEX = Regex("""https?://\S+""")

private val LIKE_SPECIAL_CHARS = Regex("[!%_]")

// 같은 회원이 같은 대상에 같은 본문을 다른 키로 다시 쓰면 막는 창. 더 길면 일부러 쓴 말까지 막는다
// (운영 3쌍 중 5초 간격 1쌍만 잡고 32·44분 간격은 두었다, 2026-11-04 사용자 결정, docs/plans/2026-11-04-idempotent-create.md).
private val DUPLICATE_COMMENT_WINDOW = Duration.ofMinutes(1)

@Service
class CommentService(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val memberRepository: MemberRepository,
    private val commentReactionRepository: CommentReactionRepository,
    private val supabaseStorageService: SupabaseStorageService,
    private val eventPublisher: ApplicationEventPublisher,
    private val advisoryLock: AdvisoryLock,
) {

    @Transactional(readOnly = true)
    fun getComments(postId: UUID, currentUserId: UUID?): List<CommentResponse> {
        val post = postRepository.findById(postId).orElseThrow { PostNotFoundException(postId) }
        // 비공개 글의 댓글도 상세 조회와 동일한 기준으로 소유자에게만 보여준다.
        PostVisibility.assertVisible(post, currentUserId, postId)

        val comments = commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)

        val commentIds = comments.map { it.id }
        // 좋아요 수는 DB에서 GROUP BY로 센다(docs/BE-ARCHITECTURE.md §7-8) - 예전에는 좋아요 행을 전부 읽어 메모리에서 셌다
        val likeCounts =
            if (commentIds.isEmpty()) {
                emptyMap()
            } else {
                commentReactionRepository.countByCommentIdIn(commentIds).associate { it.commentId to it.count.toInt() }
            }

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
                    comment.member?.let { authorOf(it) }
                        ?: CommentAuthor(UUID.randomUUID(), DELETED_MEMBER_NICKNAME, null) // Should not happen with consistent DB

                toResponse(comment, author, isLiked = userLikes.contains(comment.id), likeCount = likeCounts[comment.id] ?: 0)
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

    /** 내 댓글 목록을 cursor로 이어 읽는다(docs/PAGINATION.md). size+1개 읽어 다음 쪽 유무를 정한다. */
    @Transactional(readOnly = true)
    fun getMyCommentsByCursor(userId: UUID, cursor: String?, size: Int): MyCommentPageResponse {
        val limit = Paging.coerceSize(size)
        val after = cursor?.let { CursorCodec.decode(it, MY_COMMENTS_SORT, MY_COMMENTS_KEYS).keys }
        val rows =
            if (after == null) {
                commentRepository.findMyCommentsFirst(userId, Limit.of(limit + 1))
            } else {
                commentRepository.findMyCommentsAfter(userId, after[0] as LocalDateTime, after[1] as UUID, Limit.of(limit + 1))
            }
        val page = rows.take(limit)
        val nextCursor = page.lastOrNull().takeIf { rows.size > limit }?.let { CursorCodec.encode(MY_COMMENTS_SORT, listOf(it.createdAt, it.id)) }

        return MyCommentPageResponse(content = page.map { MyCommentResponse.from(it) }, nextCursor = nextCursor)
    }

    @Transactional(readOnly = true)
    fun findByIdempotencyKey(userId: UUID, idempotencyKey: UUID): CommentResponse? {
        val comment = commentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey) ?: return null
        val member =
            memberRepository.findByIdOrNull(userId)
                ?: throw IllegalStateException("User not found")

        return toCommentResponse(comment, member)
    }

    @Transactional
    fun createComment(
        postId: UUID,
        userId: UUID,
        content: String?,
        images: List<String>?,
        idempotencyKey: UUID? = null,
    ): CommentResponse {
        validateContent(content, images)

        val post =
            postRepository.findByIdOrNull(postId)
                ?: throw PostNotFoundException(postId)
        // 조회·댓글 목록과 동일한 기준: 읽을 수 없는 비공개 글에는 댓글도 달 수 없다.
        PostVisibility.assertVisible(post, userId, postId)
        val member =
            memberRepository.findByIdOrNull(userId)
                ?: throw IllegalStateException("User not found")
        // 읽기·좋아요·북마크는 막지 않고 글쓰기·댓글쓰기만 막는다(docs/plans/2026-09-28-auth-hardening.md
        // "확정된 결정들" 참고). 위에서 이미 조회한 member를 그대로 써 추가 쿼리가 없다.
        EmailVerificationGate.require(member, "create a comment")

        val finalContent = buildFinalContent(content, images)

        // 이미지가 있으면 재시도마다 URL이 달라 본문으로 비교할 수 없다 - 그 경우는 키만 본다.
        if (idempotencyKey != null && images.isNullOrEmpty()) {
            advisoryLock.lockForTransaction("comment-create:$userId:$postId:$finalContent")

            // 잠금을 기다리는 동안 같은 키의 요청이 먼저 끝났으면 그 댓글이 곧 이 요청의 결과다 - 아래 시간 창에 걸려 409가 되면 안 된다.
            commentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.let {
                return toCommentResponse(it, member)
            }

            if (commentRepository.existsRecentComment(userId, postId, finalContent, LocalDateTime.now().minus(DUPLICATE_COMMENT_WINDOW))) {
                throw DuplicateCommentException()
            }
        }

        val comment =
            TableComment(
                postId = postId,
                userId = userId,
                content = finalContent,
                linkUrl = extractFirstNonImageUrl(finalContent),
                idempotencyKey = idempotencyKey,
            )
        val saved = commentRepository.save(comment)

        // 알림·링크 프리뷰는 요청 경로 밖(커밋 후 별도 Lambda)에서 처리한다 - CommentPostProcessService 참고.
        // 알림 조건(루트 댓글만, 자기 자신 제외)은 지금 판정해 이벤트에 실어 보낸다 - 이 함수는 루트 댓글만 만든다
        // (답글은 createReply). 예전의 parentId 인자는 HTTP로 넘어오는 길이 없어 2026-10-26에 지웠다.
        eventPublisher.publishEvent(
            CommentPostProcessEvent(commentId = saved.id, notify = post.userId != userId),
        )

        return toCommentResponse(saved, member)
    }

    @Transactional
    fun createReply(
        parentId: UUID,
        userId: UUID,
        content: String?,
        images: List<String>?,
        idempotencyKey: UUID? = null,
    ): CommentResponse {
        validateContent(content, images)

        val parent =
            commentRepository.findByIdOrNull(parentId)
                ?: throw CommentNotFoundException(parentId)

        // Depth Check (Max Depth 1)
        if (parent.parentId != null) {
            throw InvalidInputException(
                "Reply to reply is not allowed (Max Depth 1)",
            )
        }

        val post =
            postRepository.findByIdOrNull(parent.postId)
                ?: throw PostNotFoundException(parent.postId)
        // 조회·댓글 목록과 동일한 기준: 읽을 수 없는 비공개 글에는 답글도 달 수 없다.
        PostVisibility.assertVisible(post, userId, parent.postId)

        val member =
            memberRepository.findByIdOrNull(userId)
                ?: throw IllegalStateException("User not found")
        EmailVerificationGate.require(member, "create a reply")

        val finalContent = buildFinalContent(content, images)

        // createComment와 같은 규칙이고 대상만 부모 댓글이다.
        if (idempotencyKey != null && images.isNullOrEmpty()) {
            advisoryLock.lockForTransaction("reply-create:$userId:$parentId:$finalContent")

            commentRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey)?.let {
                return toCommentResponse(it, member)
            }

            if (commentRepository.existsRecentReply(userId, parentId, finalContent, LocalDateTime.now().minus(DUPLICATE_COMMENT_WINDOW))) {
                throw DuplicateCommentException()
            }
        }

        val comment =
            TableComment(
                postId = parent.postId,
                userId = userId,
                parentId = parentId,
                content = finalContent,
                linkUrl = extractFirstNonImageUrl(finalContent),
                idempotencyKey = idempotencyKey,
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
                ?: throw CommentNotFoundException(commentId)

        if (comment.userId != userId) {
            throw ForbiddenException("Not authorized to delete this comment")
        }

        val imageUrls = extractManagedImageUrls(comment.content).filterNot { isReferencedOutsideComment(it, commentId) }

        // 톰스톤은 comments row가 살아남아 FK 캐스케이드가 발동하지 않으므로 명시 삭제가 유일한 수단이다.
        // 하드 삭제 경로에서는 comment_reactions FK ON DELETE CASCADE 가 백스톱으로 남는다.
        commentReactionRepository.deleteByCommentId(commentId)

        val hasReplies = commentRepository.existsByParentId(commentId)
        if (hasReplies) {
            comment.content = DELETED_COMMENT_CONTENT
            comment.isDeleted = true
            comment.updatedAt = LocalDateTime.now()
            commentRepository.save(comment)
        } else {
            commentRepository.delete(comment)
        }

        // 이미지 삭제는 반드시 커밋 이후에 실행한다 - updateComment와 동일한 이유: 위 DB 작업이
        // 실패해 트랜잭션이 롤백되면 댓글은 DB에 그대로 남았는데 파일만 사라진 상태가 된다.
        if (imageUrls.isNotEmpty()) {
            TransactionCallbacks.afterCommit { supabaseStorageService.deleteObjectsByPublicUrls(imageUrls) }
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
            .distinct()
            .filterNot { isReferencedOutsidePost(it, postId) }
        if (imageUrls.isNotEmpty()) {
            TransactionCallbacks.afterCommit { supabaseStorageService.deleteObjectsByPublicUrls(imageUrls) }
        }
    }

    private fun extractManagedImageUrls(content: String): List<String> = URL_REGEX.findAll(content)
        .map { it.value }
        .filter { supabaseStorageService.isManagedUrl(it) }
        .toList()

    // 삭제 경로는 본문에 든 우리 버킷 URL을 지우지만, 그 파일을 누가 올렸는지는 모른다. 그래서 남의
    // 이미지 URL을 내 댓글에 붙여넣고 그 댓글을 지우거나 고치면 원래 주인의 파일까지 지워졌다. 다른
    // 댓글 본문이나 회원 아바타가 아직 쓰는 URL은 지우지 않는다(남은 파일은 OrphanImageCleanupRunner
    // 같은 참조 스캔이 아무도 안 쓰게 됐을 때 회수한다).
    private fun isReferencedOutsideComment(url: String, commentId: UUID): Boolean = commentRepository.existsOtherCommentContaining(containsPattern(url), commentId) || memberRepository.existsByImage(url)

    private fun isReferencedOutsidePost(url: String, postId: UUID): Boolean = commentRepository.existsCommentOutsidePostContaining(containsPattern(url), postId) || memberRepository.existsByImage(url)

    // LIKE 와일드카드(%, _)와 이스케이프 문자(!)를 리터럴로 바꾼다 - 저장소 쿼리의 ESCAPE '!'와 짝이다.
    private fun containsPattern(url: String): String = "%" + url.replace(LIKE_SPECIAL_CHARS) { "!${it.value}" } + "%"

    @Transactional
    fun updateComment(
        commentId: UUID,
        userId: UUID,
        content: String?,
        images: List<String>?,
    ): CommentResponse {
        validateContent(content, images)

        val comment =
            commentRepository.findByIdOrNull(commentId)
                ?: throw CommentNotFoundException(commentId)

        if (comment.userId != userId) {
            throw ForbiddenException("Not authorized to update this comment")
        }

        if (comment.isDeleted) {
            throw CommentDeletedException(commentId)
        }

        val previousImageUrls = extractManagedImageUrls(comment.content)

        val finalContent = buildFinalContent(content, images)

        comment.content = finalContent
        comment.linkUrl = extractFirstNonImageUrl(finalContent)
        // 링크가 바뀌었을 수 있으니 이전 프리뷰는 비우고, 후처리 job이 다시 채운다.
        comment.linkTitle = null
        comment.linkDescription = null
        comment.linkOgImage = null
        comment.updatedAt = LocalDateTime.now()
        val updated = commentRepository.save(comment)

        // 이미지 삭제는 반드시 커밋 이후에 실행한다 - 아래 memberRepository 조회가 실패하면
        // 트랜잭션이 롤백되는데, 여기서 바로 지우면 DB엔 옛 URL이 남고 파일은 이미 사라진
        // 상태가 되어 깨진 이미지가 된다. 본문 텍스트에 직접 써둔 URL은 새 content에도
        // 남으므로 차집합에서 빠져 보존된다.
        val removedImageUrls = (previousImageUrls - extractManagedImageUrls(finalContent).toSet())
            .filterNot { isReferencedOutsideComment(it, commentId) }
        if (removedImageUrls.isNotEmpty()) {
            TransactionCallbacks.afterCommit { supabaseStorageService.deleteObjectsByPublicUrls(removedImageUrls) }
        }

        // 알림은 보내지 않는다(수정은 알림 대상 아님) - 링크 프리뷰만 커밋 후 별도 Lambda에서 갱신.
        eventPublisher.publishEvent(CommentPostProcessEvent(commentId = updated.id, notify = false))

        val member =
            memberRepository.findByIdOrNull(userId)
                ?: throw IllegalStateException("User not found")

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

    // 등록·답글·수정 세 경로가 같은 입력 규칙을 쓴다
    private fun validateContent(content: String?, images: List<String>?) {
        if (content.isNullOrBlank() && images?.isEmpty() ?: true) {
            throw InvalidInputException("Content or image must be provided")
        }

        if ((images?.size ?: 0) > CommentLimits.MAX_IMAGES) {
            throw InvalidInputException("Comment images cannot exceed ${CommentLimits.MAX_IMAGES}")
        }

        if ((content?.toByteArray(Charsets.UTF_8)?.size ?: 0) > CommentLimits.MAX_CONTENT_BYTES) {
            throw InvalidInputException("Comment content cannot exceed ${CommentLimits.MAX_CONTENT_BYTES} bytes")
        }
    }

    // 등록·답글·수정의 응답 - 방금 쓴 댓글이라 좋아요는 아직 0이고(수정도 지금처럼 0/false로 낸다) 삭제 상태가 아니다
    private fun toCommentResponse(comment: TableComment, member: TableMember) = toResponse(comment, authorOf(member), isLiked = false, likeCount = 0)

    private fun authorOf(member: TableMember) = CommentAuthor(member.id!!, member.publicNickname ?: DELETED_MEMBER_NICKNAME, member.publicImage)

    // 목록·단건이 함께 쓰는 변환. 삭제된(톰스톤) 댓글은 본문을 안내 문구로 바꾸고 좋아요·링크 미리보기를 싣지 않는다 - 삭제와
    // 좋아요가 동시에 일어난 경쟁 상황의 잔여값이 표현 계층까지 새지 않도록 여기서도 명시적으로 0/false로 고정한다.
    private fun toResponse(comment: TableComment, author: CommentAuthor, isLiked: Boolean, likeCount: Int) = CommentResponse(
        id = comment.id,
        content = if (comment.isDeleted) DELETED_COMMENT_CONTENT else comment.content,
        isDeleted = comment.isDeleted,
        createdAt = comment.createdAt,
        author = author,
        isLiked = !comment.isDeleted && isLiked,
        likeCount = if (comment.isDeleted) 0 else likeCount,
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
