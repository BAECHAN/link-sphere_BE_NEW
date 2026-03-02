package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TargetType
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
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
        private val commentRepository: com.example.linksphere.domain.comment.CommentRepository,
        private val eventPublisher: ApplicationEventPublisher,
        private val urlMetadataExtractor: UrlMetadataExtractor
) {

    private val logger = LoggerFactory.getLogger(PostService::class.java)

    @Transactional
    fun createPost(userId: UUID, request: PostCreateRequest): PostResponse {
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
        val responses = postPage.content.map { convertToResponse(it, currentUserId) }
        return PostPageResponse.from(postPage, responses)
    }

    @Transactional
    fun getPostById(id: UUID, currentUserId: UUID?): PostResponse {
        postRepository.incrementViewCount(id)
        val post = postRepository.findById(id).orElseThrow { PostNotFoundException(id) }
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

    private fun convertToResponse(post: TablePost, currentUserId: UUID?): PostResponse {
        val postId = post.id ?: throw IllegalStateException("Post ID cannot be null")

        val author =
                memberRepository.findById(post.userId).orElseThrow {
                    IllegalArgumentException("Member not found with id: ${post.userId}")
                }
        val authorSummary =
                UserSummary(
                        id = author.id ?: throw IllegalStateException("User ID cannot be null"),
                        nickname = author.nickname,
                        image = author.image
                )

        val bookmarkCount = bookmarkRepository.countByPostId(postId).toInt()
        val isBookmarked =
                if (currentUserId != null) bookmarkRepository.existsByUserIdAndPostId(currentUserId, postId)
                else false

        val reactionCount = reactionRepository.countByTargetIdAndTargetType(postId, TargetType.POST).toInt()
        val isReacted =
                if (currentUserId != null) {
                    reactionRepository.existsByTargetIdAndTargetTypeAndUserId(postId, TargetType.POST, currentUserId)
                } else false

        return PostResponse(
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
                                likeCount = reactionCount,
                                commentCount = commentRepository.countByPostId(postId).toInt(),
                                bookmarkCount = bookmarkCount
                        ),
                userInteractions = PostUserInteractions(isLiked = isReacted, isBookmarked = isBookmarked),
                author = authorSummary
        )
    }
}
