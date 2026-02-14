package com.example.linksphere.domain.category

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<TableCategory, Long> {
    fun findAllByOrderBySortOrderAsc(): List<TableCategory>

    fun findBySlug(slug: String): TableCategory?

    fun findAllByIdIn(ids: List<Long>): List<TableCategory>
}
