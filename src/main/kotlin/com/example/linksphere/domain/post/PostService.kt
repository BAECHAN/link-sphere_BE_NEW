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
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

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
        private val urlMetadataExtractor: UrlMetadataExtractor
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
                        isPrivate = request.isPrivate
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
                            existingTags = metadata.tags
                    )
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
            currentUserId: UUID?
    ): PostPageResponse {
        val pageable = PageRequest.of(page, size)
        val postPage = postRepository.findPosts(category, search, filter, nickname, currentUserId, pageable)
        val posts = postPage.content

        if (posts.isEmpty()) return PostPageResponse.from(postPage, emptyList())

        val postIds = posts.mapNotNull { it.id }

        // Batch fetch: authors
        val authorMap =
                memberRepository.findAllById(posts.map { it.userId }.distinct())
                        .associate { m -> val id = m.id!!; id to UserSummary(id, m.nickname, m.image) }

        // Batch fetch: bookmarks
        val allBookmarks = bookmarkRepository.findAllByPostIdIn(postIds)
        val bookmarkCountMap = allBookmarks.groupingBy { it.postId }.eachCount()
        val bookmarkedPostIds =
                if (currentUserId != null)
                        bookmarkRepository.findAllByUserIdAndPostIdIn(currentUserId, postIds)
                                .map { it.postId }
                                .toSet()
                else emptySet()

        // Batch fetch: reactions
        val allReactions = reactionRepository.findAllByTargetIdInAndTargetType(postIds, TargetType.POST)
        val reactionCountMap = allReactions.groupingBy { it.targetId }.eachCount()
        val reactedPostIds =
                if (currentUserId != null)
                        reactionRepository
                                .findAllByUserIdAndTargetIdInAndTargetType(currentUserId, postIds, TargetType.POST)
                                .map { it.targetId }
                                .toSet()
                else emptySet()

        // Batch fetch: comment counts
        val commentCountMap =
                commentRepository.countByPostIdIn(postIds)
                        .associate { it.postId to it.count.toInt() }

        val responses =
                posts.map { post ->
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
                            commentCount = commentCountMap[postId] ?: 0
                    )
                }

        return PostPageResponse.from(postPage, responses)
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

        post.title = request.title
        post.isPrivate = request.isPrivate
        post.categories.clear()
        if (!request.categoryIds.isNullOrEmpty()) {
            post.categories.addAll(categoryRepository.findAllByIdIn(request.categoryIds))
        }

        return convertToResponse(postRepository.save(post), userId)
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
                        image = dbAuthor.image
                )

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
                isBookmarked =
                        currentUserId?.let { bookmarkRepository.existsByUserIdAndPostId(it, postId) } ?: false,
                commentCount = commentRepository.countByPostId(postId).toInt()
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
            commentCount: Int
    ): PostResponse =
            PostResponse(
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
                                    bookmarkCount = bookmarkCount
                            ),
                    userInteractions = PostUserInteractions(isLiked = isLiked, isBookmarked = isBookmarked),
                    author = author
            )
}
