package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TargetType
import com.example.linksphere.domain.member.MemberRepository
import java.util.UUID
import org.jsoup.Jsoup
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
        private val eventPublisher: ApplicationEventPublisher
) {

        private val logger = LoggerFactory.getLogger(PostService::class.java)

        @Transactional
        fun createPost(userId: UUID, request: PostCreateRequest): PostResponse {
                var title = ""
                var description: String? = null
                var ogImage: String? = null
                val tags = mutableListOf<String>()
                var pageContent: String? = null

                try {
                        val doc =
                                Jsoup.connect(request.url)
                                        .userAgent(
                                                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                                        )
                                        .referrer("http://google.com")
                                        .timeout(5000)
                                        .get()

                        title =
                                doc.select("meta[property=og:title]")
                                        .attr("content")
                                        .ifEmpty { doc.title() }
                                        .ifEmpty { request.url }

                        description = doc.select("meta[property=og:description]").attr("content")
                        ogImage = doc.select("meta[property=og:image]").attr("content")

                        // Domain extraction for tags
                        val host = java.net.URI(request.url).host.replace("www.", "")
                        if (host.isNotEmpty()) {
                                tags.add(host)
                        }

                        // 페이지 본문 텍스트 추출 (AI 분석용)
                        pageContent =
                                doc.body().text().replace("\\s+".toRegex(), " ").trim().take(5000)
                } catch (e: Exception) {
                        logger.error("[Crawling] 크롤링 실패", e)
                        title = request.url
                }

                // 카테고리 ID로 카테고리 엔티티 조회
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
                                description = description,
                                tags = tags,
                                categories = categories,
                                ogImage = ogImage,
                                aiStatus =
                                        if (pageContent != null) AiStatus.PENDING else AiStatus.NONE
                        )
                val savedPost = postRepository.save(newPost)

                // AI 분석은 비동기로 처리 (크롤링 성공 시에만)
                if (pageContent != null) {
                        logger.info("[AI Async] PostCreatedEvent 발행 - postId: ${savedPost.id}")
                        eventPublisher.publishEvent(
                                PostCreatedEvent(
                                        postId = savedPost.id!!,
                                        userId = userId,
                                        title = title,
                                        description = description,
                                        content = pageContent,
                                        existingTags = tags.toList()
                                )
                        )
                }

                return convertToResponse(savedPost, userId)
        }

        fun getAllPosts(
                category: String?,
                search: String?,
                filter: String?,
                page: Int,
                size: Int,
                currentUserId: UUID?
        ): PostPageResponse {
                val pageable = PageRequest.of(page, size)

                // Use the custom repository method for dynamic filtering
                val postPage =
                        postRepository.findPosts(category, search, filter, currentUserId, pageable)

                val responses = postPage.content.map { convertToResponse(it, currentUserId) }
                return PostPageResponse.from(postPage, responses)
        }

        fun getPostById(id: UUID, currentUserId: UUID?): PostResponse {
                val post =
                        postRepository.findById(id).orElseThrow {
                                IllegalArgumentException("Post not found with id: $id")
                        }
                return convertToResponse(post, currentUserId)
        }

        private fun convertToResponse(post: TablePost, currentUserId: UUID?): PostResponse {
                val postId = post.id ?: throw IllegalStateException("Post ID cannot be null")

                val author =
                        memberRepository.findById(post.userId).orElseThrow {
                                IllegalArgumentException("Member not found with id: ${post.userId}")
                        }
                val authorSummary =
                        UserSummary(
                                id = author.id
                                                ?: throw IllegalStateException(
                                                        "User ID cannot be null"
                                                ),
                                nickname = author.nickname,
                                image = author.image
                        )

                val bookmarkCount = bookmarkRepository.countByPostId(postId)
                val isBookmarked =
                        if (currentUserId != null) {
                                bookmarkRepository.existsByUserIdAndPostId(currentUserId, postId)
                        } else {
                                false
                        }

                val reactionCount =
                        reactionRepository.countByTargetIdAndTargetType(postId, TargetType.POST)
                val isReacted =
                        if (currentUserId != null) {
                                reactionRepository.existsByUserIdAndTargetIdAndTargetType(
                                        currentUserId,
                                        postId,
                                        TargetType.POST
                                )
                        } else {
                                false
                        }

                return PostResponse(
                        id = postId,
                        userId = post.userId,
                        url = post.url,
                        title = post.title,
                        description = post.description,
                        tags = post.tags,
                        categories =
                                post.categories.map { CategoryResponse.from(it) }.sortedBy {
                                        it.id
                                },
                        ogImage = post.ogImage,
                        aiSummary = post.aiSummary,
                        createdAt = post.createdAt,
                        aiStatus = post.aiStatus,
                        stats =
                                PostStats(
                                        viewCount = post.viewCount ?: 0,
                                        likeCount = reactionCount,
                                        commentCount = 0, // TODO: Implement comment count
                                        bookmarkCount = bookmarkCount
                                ),
                        userInteractions =
                                PostUserInteractions(
                                        isLiked = isReacted,
                                        isBookmarked = isBookmarked
                                ),
                        author = authorSummary
                )
        }
}
