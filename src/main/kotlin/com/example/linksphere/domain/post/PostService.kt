package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
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
            val doc = Jsoup.connect(request.url).get()
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
            pageContent = doc.body().text().replace("\\s+".toRegex(), " ").trim().take(5000)
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
                        aiStatus = if (pageContent != null) AiStatus.PENDING else AiStatus.NONE
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

        return PostResponse.from(savedPost)
    }

    fun getAllPosts(page: Int, size: Int): PostPageResponse {
        val pageable = PageRequest.of(page, size)
        val postPage = postRepository.findAllByOrderByCreatedAtDesc(pageable)
        return PostPageResponse.from(postPage)
    }

    fun getPostById(id: UUID): PostResponse {
        val post =
                postRepository.findById(id).orElseThrow {
                    IllegalArgumentException("Post not found with id: $id")
                }
        return PostResponse.from(post)
    }

    fun getPostsByCategorySlug(slug: String, page: Int, size: Int): PostPageResponse {
        val pageable = PageRequest.of(page, size)
        val postPage = postRepository.findByCategoriesSlugOrderByCreatedAtDesc(slug, pageable)
        return PostPageResponse.from(postPage)
    }
}
