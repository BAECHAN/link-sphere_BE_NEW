package com.example.linksphere.domain.interaction

import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface BookmarkRepository : JpaRepository<TableBookmark, BookmarkId> {
    fun countByPostId(postId: UUID): Int
    fun existsByUserIdAndPostId(userId: UUID, postId: UUID): Boolean
}
