package com.example.linksphere.domain.comment

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
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

    // "내 댓글" 목록용 - 원글 제목을 같이 내려줘야 해서 join fetch로 N+1을 막는다.
    // 톰스톤(isDeleted)은 내용이 이미 지워져 보여줄 게 없어 제외한다. 가시성 게이트
    // (p.isPrivate = false OR p.userId = :userId)는 PostRepositoryImpl의 목록 조회
    // 게이트와 동일한 기준 - 댓글을 단 뒤 원글이 비공개로 전환되면 남의 글 제목이
    // 새지 않도록 막는다. count 쿼리는 fetch join을 못 쓰므로 별도로 명시한다.
    @Query(
        value = "SELECT c FROM TableComment c JOIN FETCH c.post p " +
            "WHERE c.userId = :userId AND c.isDeleted = false " +
            "AND (p.isPrivate = false OR p.userId = :userId) " +
            "ORDER BY c.createdAt DESC",
        countQuery = "SELECT COUNT(c) FROM TableComment c JOIN c.post p " +
            "WHERE c.userId = :userId AND c.isDeleted = false " +
            "AND (p.isPrivate = false OR p.userId = :userId)",
    )
    fun findMyComments(@Param("userId") userId: UUID, pageable: Pageable): Page<TableComment>

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
