package com.example.linksphere.domain.post

import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PostRepository : JpaRepository<TablePost, UUID>, PostRepositoryCustom {

    fun findByCategoriesSlug(slug: String): List<TablePost>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<TablePost>

    fun findByCategoriesSlugOrderByCreatedAtDesc(slug: String, pageable: Pageable): Page<TablePost>
}
