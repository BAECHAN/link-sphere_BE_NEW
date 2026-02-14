package com.example.linksphere.domain.category

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/common/category-option")
class CategoryController(private val categoryService: CategoryService) {

    @GetMapping
    fun getAllCategories(): List<CategoryResponse> {
        return categoryService.getAllCategories()
    }

    @GetMapping("/{slug}")
    fun getCategoryBySlug(@PathVariable slug: String): CategoryResponse {
        return categoryService.getCategoryBySlug(slug)
    }
}
