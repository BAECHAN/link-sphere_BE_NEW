package com.example.linksphere.domain.post

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository

interface PostRepository : JpaRepository<TablePost, UUID> {

    fun findByCategoriesSlug(slug: String): List<TablePost>
}
