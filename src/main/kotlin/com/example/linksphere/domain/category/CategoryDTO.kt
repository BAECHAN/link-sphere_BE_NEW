package com.example.linksphere.domain.category

/** 다른 도메인이 카테고리를 다룰 때 받는 값 - 엔티티(TableCategory)는 category 패키지 밖으로 내보내지 않는다. */
data class CategoryInfo(
    val id: Long,
    val name: String,
    val slug: String,
) {
    companion object {
        fun from(entity: TableCategory) = CategoryInfo(
            id = entity.id!!,
            name = entity.name,
            slug = entity.slug,
        )
    }
}

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
