package com.example.linksphere.domain.comment

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

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

    // 게시글 삭제 시 이미지 정리용 — 스칼라 프로젝션이라 TableComment(및 그 post 지연연관관계)가
    // 영속성 컨텍스트에 올라가지 않는다. 같은 트랜잭션에서 postRepository.delete(post)가 뒤따르므로
    // 엔티티로 로드하면 flush 시점에 TransientObjectException이 난다(실제 배포 후 재현됨).
    @Query("SELECT c.content FROM TableComment c WHERE c.postId = :postId")
    fun findAllContentByPostId(@Param("postId") postId: UUID): List<String>

    // 고아 이미지 정리 도구(OrphanImageCleanupRunner)용 — 위와 같은 이유로 스칼라 프로젝션을 쓴다.
    @Query("SELECT c.content FROM TableComment c")
    fun findAllContent(): List<String>
}
