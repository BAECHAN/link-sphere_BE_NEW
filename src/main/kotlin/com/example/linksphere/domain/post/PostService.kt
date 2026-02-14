package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.infra.ai.GeminiService
import java.util.UUID
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class PostService(
        private val postRepository: PostRepository,
        private val categoryRepository: CategoryRepository,
        private val geminiService: GeminiService
) {

    private val logger = LoggerFactory.getLogger(PostService::class.java)

    @Transactional
    fun createPost(userId: UUID, request: PostCreateRequest): PostResponse {
        var title = ""
        var description: String? = null
        var ogImage: String? = null
        val tags = mutableListOf<String>()
        var aiSummary: String? = null

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

            // AI Analysis
            try {
                val content = doc.body().text().replace("\\s+".toRegex(), " ").trim().take(5000)
                val analysisResult = geminiService.analyzeContent(title, description, content)

                aiSummary = analysisResult.summary
                if (analysisResult.tags.isNotEmpty()) {
                    // Add distinct tags
                    val newTags = analysisResult.tags.filter { !tags.contains(it) }
                    tags.addAll(newTags)
                }
                logger.info("[AI] 분석 성공 - summary: ${aiSummary?.take(50)}, tags: $tags")
            } catch (e: Exception) {
                logger.error("[AI] 분석 실패", e)
                // Continue even if AI analysis fails
            }
        } catch (e: Exception) {
            logger.error("[Crawling] 크롤링 실패", e)
            title = request.url
            // 크롤링 실패 시 URL을 제목으로 사용하고 나머지 필드는 null 처리
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
                        aiSummary = aiSummary
                )
        val savedPost = postRepository.save(newPost)
        return PostResponse.from(savedPost)
    }

    fun getAllPosts(): List<PostResponse> {
        return postRepository.findAll().map { PostResponse.from(it) }
    }

    fun getPostById(id: UUID): PostResponse {
        val post =
                postRepository.findById(id).orElseThrow {
                    IllegalArgumentException("Post not found with id: $id")
                }
        return PostResponse.from(post)
    }

    fun getPostsByCategorySlug(slug: String): List<PostResponse> {
        return postRepository.findByCategoriesSlug(slug).map { PostResponse.from(it) }
    }
}
