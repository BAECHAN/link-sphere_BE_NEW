package com.example.linksphere.domain.comment

import java.util.UUID
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository

interface CommentRepository : JpaRepository<TableComment, UUID> {

    @EntityGraph(attributePaths = ["member"])
    fun findAllByPostIdOrderByCreatedAtAsc(postId: UUID): List<TableComment>

    fun countByPostId(postId: UUID): Long

    // For counting replies to check if we can hard delete
    fun existsByParentId(parentId: UUID): Boolean
}
