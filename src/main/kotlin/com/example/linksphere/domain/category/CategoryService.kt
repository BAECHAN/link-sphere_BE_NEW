package com.example.linksphere.domain.category

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CategoryService(private val categoryRepository: CategoryRepository) {

    fun getAllCategories(): List<CategoryResponse> {
        return categoryRepository.findAllByOrderBySortOrderAsc().map { CategoryResponse.from(it) }
    }

    fun getCategoryBySlug(slug: String): CategoryResponse {
        val category =
                categoryRepository.findBySlug(slug)
                        ?: throw IllegalArgumentException("Category not found with slug: $slug")
        return CategoryResponse.from(category)
    }
}
