package com.example.linksphere.domain.category

import com.example.linksphere.global.common.ApiResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/common/category-option")
class CategoryController(private val categoryService: CategoryService) {

    @GetMapping
    fun getAllCategories(): ApiResponse<List<CategoryResponse>> =
            ApiResponse(HttpStatus.OK.value(), "Categories retrieved", categoryService.getAllCategories())

    @GetMapping("/{slug}")
    fun getCategoryBySlug(@PathVariable slug: String): ApiResponse<CategoryResponse> =
            ApiResponse(HttpStatus.OK.value(), "Category retrieved", categoryService.getCategoryBySlug(slug))
}
