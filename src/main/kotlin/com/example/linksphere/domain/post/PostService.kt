package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TargetType
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID

@Service
@Transactional(readOnly = true)
class PostService(
    private val postRepository: PostRepository,
    private val categoryRepository: CategoryRepository,
    private val memberRepository: MemberRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val reactionRepository: ReactionRepository,
    private val commentRepository: CommentRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val urlMetadataExtractor: UrlMetadataExtractor,
) {

    private val logger = LoggerFactory.getLogger(PostService::class.java)

    @Transactional
    fun createPost(userId: UUID, request: PostCreateRequest): PostResponse {
        validateUrl(request.url)
        val metadata = urlMetadataExtractor.extract(request.url)

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
                url = request.url,
                title = title,
                description = metadata.description,
                tags = metadata.tags.toMutableList(),
                categories = categories,
                ogImage = metadata.ogImage,
                aiStatus = if (metadata.pageContent != null) AiStatus.PENDING else AiStatus.NONE,
                isPrivate = request.isPrivate,
            )
        val savedPost = postRepository.save(newPost)

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
        val bookmarkFolderMap = myBookmarks.associate { it.postId to it.folderId }

        val allReactions = reactionRepository.findAllByTargetIdInAndTargetType(postIds, TargetType.POST)
        val reactionCountMap = allReactions.groupingBy { it.targetId }.eachCount()
        val reactedPostIds =
            if (currentUserId != null) {
                reactionRepository
                    .findAllByUserIdAndTargetIdInAndTargetType(currentUserId, postIds, TargetType.POST)
                    .map { it.targetId }
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
                bookmarkFolderId = bookmarkFolderMap[postId],
                commentCount = commentCountMap[postId] ?: 0,
            )
        }
    }

    @Transactional
    fun getPostById(id: UUID, currentUserId: UUID?): PostResponse {
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
        postRepository.incrementViewCount(id)
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
        val newUrl = request.url?.takeIf { it != post.url }
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
        postRepository.delete(post)
    }

    private fun validateUrl(url: String) {
        if (url.isBlank()) throw IllegalArgumentException("URL cannot be blank")
        try {
            val uri = URI(url)
            if (uri.scheme !in listOf("http", "https")) {
                throw IllegalArgumentException("URL must use http or https scheme")
            }
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Invalid URL format: $url")
        }
    }

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

        val myBookmark = currentUserId?.let {
            bookmarkRepository.findById(com.example.linksphere.domain.interaction.BookmarkId(it, postId)).orElse(null)
        }
        return buildPostResponse(
            post = post,
            postId = postId,
            author = author,
            likeCount = reactionRepository.countByTargetIdAndTargetType(postId, TargetType.POST).toInt(),
            isLiked =
            currentUserId?.let {
                reactionRepository.existsByTargetIdAndTargetTypeAndUserId(postId, TargetType.POST, it)
            } ?: false,
            bookmarkCount = bookmarkRepository.countByPostId(postId).toInt(),
            isBookmarked = myBookmark != null,
            bookmarkFolderId = myBookmark?.folderId,
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
        bookmarkFolderId: UUID? = null,
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
            bookmarkFolderId = bookmarkFolderId,
        ),
        author = author,
    )
}
