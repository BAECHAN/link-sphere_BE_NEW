package com.example.linksphere.domain.category

import com.example.linksphere.global.common.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/common/category-option")
class CategoryController(private val categoryService: CategoryService) {

    @GetMapping
    fun getAllCategories(): ApiResponse<List<CategoryResponse>> {
        val categories = categoryService.getAllCategories()
        return ApiResponse(HttpStatus.OK.value(), "Categories retrieved", categories)
    }

    @GetMapping("/{slug}")
    fun getCategoryBySlug(@PathVariable slug: String): ApiResponse<CategoryResponse> {
        val category = categoryService.getCategoryBySlug(slug)
        return ApiResponse(HttpStatus.OK.value(), "Category retrieved", category)
    }
}
