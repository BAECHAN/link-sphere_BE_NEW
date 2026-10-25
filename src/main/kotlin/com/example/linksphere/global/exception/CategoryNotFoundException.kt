package com.example.linksphere.global.exception

/**
 * slug로 찾은 카테고리가 없을 때(CategoryService.getCategoryBySlug). 예전에는 IllegalArgumentException의 공통
 * 매핑으로 404 NOT_FOUND가 나갔다 - 무엇이 없는지 code로 알 수 있게 전용 예외로 낸다
 * (docs/plans/2026-10-25-be-architecture-audit.md PR3).
 */
class CategoryNotFoundException(slug: String) : RuntimeException("Category not found with slug: $slug")
