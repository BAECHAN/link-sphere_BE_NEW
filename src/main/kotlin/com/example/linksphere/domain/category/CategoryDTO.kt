package com.example.linksphere.domain.category

data class CategoryResponse(
    val id: Long,
    val name: String,
) {
    companion object {
        fun from(entity: TableCategory) = CategoryResponse(
            id = entity.id!!,
            name = entity.name,
        )
    }
}
