package com.example.linksphere.domain.post

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

interface PostRepositoryCustom {
    fun findPosts(
        category: String?,
        search: String?,
        filter: String?,
        nickname: String?,
        currentUserId: UUID?,
        pageable: Pageable,
    ): Page<TablePost>
}
