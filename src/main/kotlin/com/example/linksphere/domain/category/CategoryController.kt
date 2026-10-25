package com.example.linksphere.domain.category

import com.example.linksphere.global.common.ApiResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Duration

@Tag(name = "카테고리", description = "게시글 카테고리 옵션 조회")
@RestController
@RequestMapping("/common/category-option")
class CategoryController(private val categoryService: CategoryService) {

    @Operation(summary = "카테고리 전체 목록 조회")
    @SecurityRequirements
    @GetMapping
    fun getAllCategories(): ResponseEntity<ApiResponse<List<CategoryResponse>>> = ResponseEntity.ok()
        .cacheControl(CATEGORY_LIST_CACHE_CONTROL)
        .body(ApiResponse(HttpStatus.OK.value(), "Categories retrieved", categoryService.getAllCategories()))

    @Operation(summary = "slug 로 카테고리 단건 조회", description = "실패: 404 CATEGORY_NOT_FOUND")
    @SecurityRequirements
    @GetMapping("/{slug}")
    fun getCategoryBySlug(@PathVariable slug: String): ApiResponse<CategoryResponse> = ApiResponse(HttpStatus.OK.value(), "Category retrieved", categoryService.getCategoryBySlug(slug))

    companion object {
        // 카테고리는 DB를 직접 고칠 때만 바뀌는 공개 참조 데이터라 브라우저가 저장해 두고 쓰게 한다 - 매번
        // Lambda에서 새로 받으면 콜드 스타트에 걸린 응답이 게시글보다 늦어 FE 피드 필터 카드의 칩이 뒤늦게 그려지며
        // 아래가 밀렸다(FE docs/SEARCH.md §10·§11). 5분 동안은 그대로 쓰고, 그 뒤 7일 안의 방문은 옛 목록을 즉시
        // 보여 주며 뒤에서 새로 받는다(2026-10-16 사용자 결정) - 카테고리를 바꾸면 방문 한 번만큼 늦게 보인다.
        // 이 값이 있으면 Spring Security 기본 헤더(no-store)는 쓰이지 않는다 - CacheControlHeadersWriter는
        // Cache-Control·Expires·Pragma가 이미 있으면 건너뛴다(spring-security-web 6.5.7 바이트코드로 확인).
        private val CATEGORY_LIST_CACHE_CONTROL: CacheControl =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic().staleWhileRevalidate(Duration.ofDays(7))
    }
}
