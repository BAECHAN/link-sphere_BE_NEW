package com.example.linksphere.domain.category

import java.time.LocalDateTime

data class CategoryResponse(
        val id: Long,
        val name: String,
        val slug: String,
        val sortOrder: Int,
        val createdAt: LocalDateTime?
) {
    companion object {
        fun from(entity: TableCategory) =
                CategoryResponse(
                        id = entity.id!!,
                        name = entity.name,
                        slug = entity.slug,
                        sortOrder = entity.sortOrder,
                        createdAt = entity.createdAt
                )
    }
}
