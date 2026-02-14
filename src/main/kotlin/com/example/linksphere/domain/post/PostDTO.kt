package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryResponse
import java.time.LocalDateTime
import java.util.UUID

data class PostCreateRequest(val url: String, val categoryIds: List<Long>? = emptyList())

data class PostResponse(
        val id: UUID,
        val userId: UUID,
        val url: String,
        val title: String,
        val description: String?,
        val tags: List<String>?,
        val categories: List<CategoryResponse>,
        val ogImage: String?,
        val aiSummary: String?,
        val viewCount: Int?,
        val createdAt: LocalDateTime?
) {
    companion object {
        fun from(entity: TablePost): PostResponse {
            return PostResponse(
                    id = entity.id!!,
                    userId = entity.userId,
                    url = entity.url,
                    title = entity.title,
                    description = entity.description,
                    tags = entity.tags,
                    categories = entity.categories.map { CategoryResponse.from(it) },
                    ogImage = entity.ogImage,
                    aiSummary = entity.aiSummary,
                    viewCount = entity.viewCount,
                    createdAt = entity.createdAt
            )
        }
    }
}
