package com.example.linksphere.domain.category

import com.example.linksphere.global.exception.CategoryNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CategoryService(private val categoryRepository: CategoryRepository) {

    fun getAllCategories(): List<CategoryResponse> = categoryRepository.findAllByOrderBySortOrderAsc().map { CategoryResponse.from(it) }

    fun getCategoryBySlug(slug: String): CategoryResponse = CategoryResponse.from(
        categoryRepository.findBySlug(slug)
            ?: throw CategoryNotFoundException(slug),
    )

    // ── 다른 도메인용 조회 - 엔티티 대신 id나 CategoryInfo를 돌려준다(글은 카테고리를 id로만 든다) ──

    /** 전체 카테고리(정렬 순서대로). 글 자동 분류가 이름·slug를 태그와 맞춰 볼 때 쓴다. */
    fun getAllCategoryInfos(): List<CategoryInfo> = categoryRepository.findAllByOrderBySortOrderAsc().map { CategoryInfo.from(it) }

    /** id로 카테고리를 찾는다. 없는 id는 결과에서 빠진다. */
    fun getCategoryInfosByIds(ids: Collection<Long>): List<CategoryInfo> = if (ids.isEmpty()) emptyList() else categoryRepository.findAllByIdIn(ids).map { CategoryInfo.from(it) }

    /** 받은 id 가운데 실제로 있는 것만 돌려준다 - 글 등록·수정에서 없는 id는 오류 없이 버린다. */
    fun filterExistingIds(ids: Collection<Long>): Set<Long> = getCategoryInfosByIds(ids).map { it.id }.toSet()

    /** 이름이나 slug가 정확히 일치하는 카테고리의 id. 하나도 없으면 빈 집합이다. */
    fun resolveIds(namesOrSlugs: Collection<String>): Set<Long> = if (namesOrSlugs.isEmpty()) {
        emptySet()
    } else {
        categoryRepository.findAllByNameInOrSlugIn(namesOrSlugs, namesOrSlugs).mapNotNull { it.id }.toSet()
    }
}
