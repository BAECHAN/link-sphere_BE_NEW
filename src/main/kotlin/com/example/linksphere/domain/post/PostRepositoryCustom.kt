package com.example.linksphere.domain.post

import java.util.UUID
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface PostRepositoryCustom {
    fun findPosts(
            category: String?,
            search: String?,
            filter: String?,
            currentUserId: UUID?,
            pageable: Pageable
    ): Page<TablePost>
}
