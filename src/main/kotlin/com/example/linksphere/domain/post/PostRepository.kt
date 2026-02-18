package com.example.linksphere.domain.post

import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostRepository : JpaRepository<TablePost, UUID>, PostRepositoryCustom {

    fun findByCategoriesSlug(slug: String): List<TablePost>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<TablePost>

    fun findByCategoriesSlugOrderByCreatedAtDesc(slug: String, pageable: Pageable): Page<TablePost>

    @Modifying
    @Query("UPDATE TablePost p SET p.viewCount = COALESCE(p.viewCount, 0) + 1 WHERE p.id = :id")
    fun incrementViewCount(@Param("id") id: UUID)
}
