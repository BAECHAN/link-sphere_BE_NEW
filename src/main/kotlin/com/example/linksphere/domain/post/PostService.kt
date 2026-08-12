package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class PostService(
    private val postRepository: PostRepository,
    private val categoryRepository: CategoryRepository,
    private val memberRepository: MemberRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val postViewRepository: PostViewRepository,
    private val postReactionRepository: PostReactionRepository,
    private val commentRepository: CommentRepository,
    private val commentService: CommentService,
    private val eventPublisher: ApplicationEventPublisher,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val safeUrlValidator: SafeUrlValidator,
) {

    private val logger = LoggerFactory.getLogger(PostService::class.java)

    @Transactional
    fun createPost(userId: UUID, request: PostCreateRequest): PostResponse {
        val url = request.url.trim()
        validateUrl(url)
        val metadata = urlMetadataExtractor.extract(url)

        val title = if (!request.title.isNullOrBlank()) request.title else metadata.title
        val categories =
            if (!request.categoryIds.isNullOrEmpty()) {
                categoryRepository.findAllByIdIn(request.categoryIds).toMutableSet()
            } else {
                mutableSetOf()
            }

        val newPost =
            TablePost(
                userId = userId,
                url = url,
                title = title,
                description = metadata.description,
                tags = metadata.tags.toMutableList(),
                categories = categories,
                ogImage = metadata.ogImage,
                aiStatus = if (metadata.pageContent != null) AiStatus.PENDING else AiStatus.NONE,
                isPrivate = request.isPrivate,
            )
        val savedPost = postRepository.save(newPost)

        val shouldBookmark = request.bookmark || !request.folderIds.isNullOrEmpty()
        if (shouldBookmark) {
            saveBookmarkWithFolders(userId, savedPost.id!!, request.folderIds.orEmpty().distinct())
        }

        if (metadata.pageContent != null) {
            logger.info("[AI Async] PostCreatedEvent 발행 - postId: ${savedPost.id}")
            eventPublisher.publishEvent(
                PostCreatedEvent(
                    postId = savedPost.id!!,
                    userId = userId,
                    title = title,
                    description = metadata.description,
                    content = metadata.pageContent,
                    existingTags = metadata.tags,
                ),
            )
        }

        return convertToResponse(savedPost, userId)
    }

    /**
     * 등록과 동시에 북마크 생성. `InteractionService.addBookmarkFolder`와 동일한 검증·insert 순서를 따른다.
     * insert가 네이티브 쿼리라 posts 행이 먼저 DB에 있어야 하므로 flush 후 실행한다.
     */
    private fun saveBookmarkWithFolders(userId: UUID, postId: UUID, folderIds: List<UUID>) {
        postRepository.flush()

        if (folderIds.isNotEmpty()) {
            val folders = bookmarkFolderRepository.findAllById(folderIds)
            val foundIds = folders.map { it.id }.toSet()
            folderIds.firstOrNull { it !in foundIds }?.let { throw BookmarkFolderNotFoundException(it) }
            folders.firstOrNull { it.userId != userId }
                ?.let { throw ForbiddenException("Cannot add bookmark to another user's folder") }
        }

        bookmarkRepository.insertIgnoreConflict(userId, postId)
        folderIds.forEach { folderId ->
            bookmarkFolderItemRepository.insertIgnoreConflict(userId, postId, folderId)
        }
    }

    fun getAllPosts(
        category: String?,
        search: String?,
        filter: String?,
        nickname: String?,
        page: Int,
        size: Int,
        currentUserId: UUID?,
    ): PostPageResponse {
        val pageable = PageRequest.of(page, size)
        val postPage = postRepository.findPosts(category, search, filter, nickname, currentUserId, pageable)

        // 검색 결과가 없으면 한/영 자판 미스매칭 보정 후보로 한 번 더 검색한다 (예: spdlqj -> 네이버)
        if (postPage.totalElements == 0L && !search.isNullOrBlank()) {
            val correctedSearch = HangulKeyboardConverter.convertIfMislayout(search)
            if (correctedSearch != null) {
                val correctedPage =
                    postRepository.findPosts(category, correctedSearch, filter, nickname, currentUserId, pageable)
                if (correctedPage.totalElements > 0L) {
                    return PostPageResponse.from(
                        correctedPage,
                        buildResponsesFromPosts(correctedPage.content, currentUserId),
                        correctedSearch,
                    )
                }
            }
        }

        return PostPageResponse.from(postPage, buildResponsesFromPosts(postPage.content, currentUserId))
    }

    /**
     * Post 리스트를 PostResponse 리스트로 변환하면서 author/likes/bookmarks/comments를 batch fetch.
     * 다른 도메인(예: BookmarkFolderService)에서 페이지 변환 시 재사용한다.
     */
    fun buildResponsesFromPosts(posts: List<TablePost>, currentUserId: UUID?): List<PostResponse> {
        if (posts.isEmpty()) return emptyList()

        val postIds = posts.mapNotNull { it.id }

        val authorMap =
            memberRepository.findAllById(posts.map { it.userId }.distinct())
                .associate { m ->
                    val id = m.id!!
                    id to UserSummary(id, m.nickname, m.image)
                }

        val allBookmarks = bookmarkRepository.findAllByPostIdIn(postIds)
        val bookmarkCountMap = allBookmarks.groupingBy { it.postId }.eachCount()
        val myBookmarks =
            if (currentUserId != null) {
                bookmarkRepository.findAllByUserIdAndPostIdIn(currentUserId, postIds)
            } else {
                emptyList()
            }
        val bookmarkedPostIds = myBookmarks.map { it.postId }.toSet()
        val folderIdsByPost: Map<UUID, List<UUID>> =
            if (currentUserId != null) {
                bookmarkFolderItemRepository.findAllByUserIdAndPostIdIn(currentUserId, postIds)
                    .groupBy({ it.postId }, { it.folderId })
            } else {
                emptyMap()
            }

        val allReactions = postReactionRepository.findAllByPostIdIn(postIds)
        val reactionCountMap = allReactions.groupingBy { it.postId }.eachCount()
        val reactedPostIds =
            if (currentUserId != null) {
                postReactionRepository
                    .findAllByUserIdAndPostIdIn(currentUserId, postIds)
                    .map { it.postId }
                    .toSet()
            } else {
                emptySet()
            }

        val commentCountMap =
            commentRepository.countByPostIdIn(postIds)
                .associate { it.postId to it.count.toInt() }

        return posts.map { post ->
            val postId = post.id ?: throw IllegalStateException("Post ID cannot be null")
            val author =
                authorMap[post.userId]
                    ?: throw IllegalArgumentException("Member not found: ${post.userId}")
            buildPostResponse(
                post = post,
                postId = postId,
                author = author,
                likeCount = reactionCountMap[postId] ?: 0,
                isLiked = postId in reactedPostIds,
                bookmarkCount = bookmarkCountMap[postId] ?: 0,
                isBookmarked = postId in bookmarkedPostIds,
                bookmarkFolderIds = folderIdsByPost[postId] ?: emptyList(),
                commentCount = commentCountMap[postId] ?: 0,
            )
        }
    }

    @Transactional
    fun getPostById(id: UUID, currentUserId: UUID?): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        // 목록/북마크 조회에는 있는 가시성 검증이 상세 조회에는 빠져 있었다.
        // 존재 여부를 알려주지 않도록 403이 아닌 404로 던진다.
        if (post.isPrivate && post.userId != currentUserId) throw PostNotFoundException(id)
        postRepository.incrementViewCount(id)
        currentUserId?.let { postViewRepository.upsertView(it, id) }
        return convertToResponse(post, currentUserId)
    }

    @Transactional
    fun updatePost(id: UUID, userId: UUID, request: PostUpdateRequest): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")

        // 제목을 비워 보내면 새 링크에서 가져오겠다는 뜻이므로 기존 제목을 유지한다(빈 제목 저장 방지).
        post.title = request.title?.takeIf { it.isNotBlank() } ?: post.title
        post.isPrivate = request.isPrivate
        post.categories.clear()
        if (!request.categoryIds.isNullOrEmpty()) {
            post.categories.addAll(categoryRepository.findAllByIdIn(request.categoryIds))
        }

        // URL이 바뀌면 기존 메타데이터·AI 요약이 옛 링크 기준으로 남으므로 생성 때와 동일하게 재수집한다.
        // 이때 제목은 사용자가 입력한 값 대신 새 링크에서 크롤링한 제목으로 덮어쓴다.
        val newUrl = request.url?.trim()?.takeIf { it != post.url }
        val metadata =
            newUrl?.let {
                validateUrl(it)
                urlMetadataExtractor.extract(it)
            }
        if (newUrl != null && metadata != null) {
            post.url = newUrl
            // 크롤링 실패 시 metadata.title은 URL 문자열이므로, 그때는 위에서 정한 제목을 그대로 둔다.
            if (metadata.pageContent != null) post.title = metadata.title
            post.description = metadata.description
            post.tags = metadata.tags.toMutableList()
            post.ogImage = metadata.ogImage
            post.aiSummary = null
            post.aiStatus = if (metadata.pageContent != null) AiStatus.PENDING else AiStatus.NONE
        }

        val savedPost = postRepository.save(post)

        val pageContent = metadata?.pageContent
        if (metadata != null && pageContent != null) {
            logger.info("[AI Async] URL 변경으로 PostCreatedEvent 발행 - postId: ${savedPost.id}")
            eventPublisher.publishEvent(
                PostCreatedEvent(
                    postId = savedPost.id!!,
                    userId = userId,
                    title = metadata.title,
                    description = metadata.description,
                    content = pageContent,
                    existingTags = metadata.tags,
                ),
            )
        }

        return convertToResponse(savedPost, userId)
    }

    @Transactional
    fun updatePostVisibility(id: UUID, userId: UUID, request: PostVisibilityUpdateRequest): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")

        post.isPrivate = request.isPrivate
        return convertToResponse(postRepository.save(post), userId)
    }

    @Transactional
    fun deletePost(id: UUID, userId: UUID) {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        if (post.userId != userId) throw ForbiddenException("You are not the owner of this post")
        // comments.post_id FK가 ON DELETE CASCADE라 댓글 row는 DB에서 자동 삭제되지만,
        // 댓글에 딸린 스토리지 이미지는 정리되지 않으므로 게시글이 지워지기 전에 먼저 정리한다.
        commentService.deleteImagesForPost(id)
        postRepository.delete(post)
    }

    private fun validateUrl(url: String) = safeUrlValidator.validate(url)

    private fun convertToResponse(post: TablePost, currentUserId: UUID?): PostResponse {
        val postId = post.id ?: throw IllegalStateException("Post ID cannot be null")

        val dbAuthor =
            memberRepository.findById(post.userId).orElseThrow {
                IllegalArgumentException("Member not found with id: ${post.userId}")
            }
        val author =
            UserSummary(
                id = dbAuthor.id ?: throw IllegalStateException("User ID cannot be null"),
                nickname = dbAuthor.nickname,
                image = dbAuthor.image,
            )

        val isBookmarked = currentUserId?.let { bookmarkRepository.existsByUserIdAndPostId(it, postId) } ?: false
        // isBookmarked 가 true 인 경우는 currentUserId != null 인 경로(위 let)를 통해서만 나올 수 있으므로
        // 컴파일러가 이 분기 안에서 currentUserId 를 non-null 로 스마트캐스트한다.
        val bookmarkFolderIds =
            if (isBookmarked) {
                bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(currentUserId, postId)
            } else {
                emptyList()
            }
        return buildPostResponse(
            post = post,
            postId = postId,
            author = author,
            likeCount = postReactionRepository.countByPostId(postId).toInt(),
            isLiked =
            currentUserId?.let {
                postReactionRepository.existsByUserIdAndPostId(it, postId)
            } ?: false,
            bookmarkCount = bookmarkRepository.countByPostId(postId).toInt(),
            isBookmarked = isBookmarked,
            bookmarkFolderIds = bookmarkFolderIds,
            commentCount = commentRepository.countByPostId(postId).toInt(),
        )
    }

    private fun buildPostResponse(
        post: TablePost,
        postId: UUID,
        author: UserSummary,
        likeCount: Int,
        isLiked: Boolean,
        bookmarkCount: Int,
        isBookmarked: Boolean,
        bookmarkFolderIds: List<UUID> = emptyList(),
        commentCount: Int,
    ): PostResponse = PostResponse(
        id = postId,
        userId = post.userId,
        url = post.url,
        title = post.title,
        description = post.description,
        tags = post.tags,
        categories = post.categories.map { CategoryResponse.from(it) }.sortedBy { it.id },
        ogImage = post.ogImage,
        aiSummary = post.aiSummary,
        createdAt = post.createdAt,
        aiStatus = post.aiStatus,
        isPrivate = post.isPrivate,
        stats =
        PostStats(
            viewCount = post.viewCount ?: 0,
            likeCount = likeCount,
            commentCount = commentCount,
            bookmarkCount = bookmarkCount,
        ),
        userInteractions = PostUserInteractions(
            isLiked = isLiked,
            isBookmarked = isBookmarked,
            bookmarkFolderIds = bookmarkFolderIds,
        ),
        author = author,
    )
}
