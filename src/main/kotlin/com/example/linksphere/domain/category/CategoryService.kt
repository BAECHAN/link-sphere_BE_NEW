package com.example.linksphere.domain.category

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CategoryService(private val categoryRepository: CategoryRepository) {

    fun getAllCategories(): List<CategoryResponse> = categoryRepository.findAllByOrderBySortOrderAsc().map { CategoryResponse.from(it) }

    fun getCategoryBySlug(slug: String): CategoryResponse = CategoryResponse.from(
        categoryRepository.findBySlug(slug)
            ?: throw IllegalArgumentException("Category not found with slug: $slug"),
    )

    /** 게시글 등록/수정 시 카테고리 배정용 - PostService가 CategoryRepository를 직접 쓰지 않게 한다. */
    fun getCategoriesByIds(ids: List<Long>): List<TableCategory> = categoryRepository.findAllByIdIn(ids)
}
