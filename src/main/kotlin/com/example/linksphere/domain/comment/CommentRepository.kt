package com.example.linksphere.domain.comment

import java.util.UUID
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostCommentCount {
    val postId: UUID
    val count: Long
}

interface CommentRepository : JpaRepository<TableComment, UUID> {

    @EntityGraph(attributePaths = ["member"])
    fun findAllByPostIdOrderByCreatedAtAsc(postId: UUID): List<TableComment>

    fun countByPostId(postId: UUID): Long

    @Query("SELECT c.postId as postId, COUNT(c) as count FROM TableComment c WHERE c.postId IN :postIds GROUP BY c.postId")
    fun countByPostIdIn(@Param("postIds") postIds: List<UUID>): List<PostCommentCount>

    fun existsByParentId(parentId: UUID): Boolean
}
