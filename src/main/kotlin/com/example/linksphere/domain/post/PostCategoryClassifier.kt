package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.TableCategory
import com.example.linksphere.infra.ai.GeminiService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 카테고리가 비어 있는 게시글에 카테고리를 자동으로 채운다.
// 1) 태그 ↔ 카테고리 name/slug 정규화 매칭 (비용 0, 결정론적)
// 2) 매칭 실패 시에만 Gemini 의미 분류로 폴백
@Component
class PostCategoryClassifier(
    private val categoryRepository: CategoryRepository,
    private val geminiService: GeminiService,
) {

    private val logger = LoggerFactory.getLogger(PostCategoryClassifier::class.java)

    fun classify(title: String, description: String?, tags: List<String>): List<TableCategory> {
        val allCategories = categoryRepository.findAllByOrderBySortOrderAsc()
        if (allCategories.isEmpty()) return emptyList()

        val normalizedTags = tags.map { it.trim().lowercase() }.toSet()
        val tagMatched =
            allCategories.filter { it.name.lowercase() in normalizedTags || it.slug.lowercase() in normalizedTags }
        if (tagMatched.isNotEmpty()) {
            logger.info("[Category] 태그 매칭 성공 - ${tagMatched.map { it.name }}")
            return tagMatched
        }

        val suggestedNames =
            geminiService.classifyCategories(title, description, tags, allCategories.map { it.name })
        if (suggestedNames.isEmpty()) return emptyList()

        val suggestedSet = suggestedNames.map { it.trim().lowercase() }.toSet()
        val aiMatched = allCategories.filter { it.name.lowercase() in suggestedSet }
        logger.info("[Category] Gemini 분류 결과 - $suggestedNames → ${aiMatched.map { it.name }}")
        return aiMatched
    }
}
