package com.example.linksphere.domain.category

import com.example.linksphere.global.common.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "카테고리", description = "게시글 카테고리 옵션 조회")
@RestController
@RequestMapping("/common/category-option")
class CategoryController(private val categoryService: CategoryService) {

    @Operation(summary = "카테고리 전체 목록 조회")
    @SecurityRequirements
    @GetMapping
    fun getAllCategories(): ApiResponse<List<CategoryResponse>> = ApiResponse(HttpStatus.OK.value(), "Categories retrieved", categoryService.getAllCategories())

    @Operation(summary = "slug 로 카테고리 단건 조회", description = "실패: 404 NOT_FOUND")
    @SecurityRequirements
    @GetMapping("/{slug}")
    fun getCategoryBySlug(@PathVariable slug: String): ApiResponse<CategoryResponse> = ApiResponse(HttpStatus.OK.value(), "Category retrieved", categoryService.getCategoryBySlug(slug))
}
