package com.example.linksphere.domain.comment

import org.springframework.data.domain.Limit
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface PostCommentCount {
    val postId: UUID
    val count: Long
}

interface CommentRepository : JpaRepository<TableComment, UUID> {

    // 작성자는 여기서 함께 읽지 않는다 - CommentService가 작성자 id를 모아 member에 한 번에 묻는다.
    fun findAllByPostIdOrderByCreatedAtAsc(postId: UUID): List<TableComment>

    fun countByPostId(postId: UUID): Long

    // "내 댓글" 목록 첫 쪽. 톰스톤(isDeleted)은 내용이 이미 지워져 보여줄 게 없어 제외한다. 원글은 여기서 함께 읽지
    // 않는다 - 제목과 공개 여부는 CommentService가 post에 묻고(PostLookupService.findVisibleTitles), 볼 수 없게 된 글의
    // 댓글은 거기서 빠진다. 예전에는 글을 join fetch해 이 쿼리가 공개 조건까지 걸었다.
    @Query(
        "SELECT c FROM TableComment c " +
            "WHERE c.userId = :userId AND c.isDeleted = false " +
            "ORDER BY c.createdAt DESC, c.id DESC",
    )
    fun findMyCommentsFirst(@Param("userId") userId: UUID, limit: Limit): List<TableComment>

    // "내 댓글" 목록 다음 쪽. 조건은 위와 같다. 앞의 상한이 있어야 인덱스로 시작 지점을 찾는다(SeekPredicate 주석 참고).
    @Query(
        "SELECT c FROM TableComment c " +
            "WHERE c.userId = :userId AND c.isDeleted = false " +
            "AND c.createdAt <= :createdAt AND (c.createdAt < :createdAt OR (c.createdAt = :createdAt AND c.id < :id)) " +
            "ORDER BY c.createdAt DESC, c.id DESC",
    )
    fun findMyCommentsAfter(
        @Param("userId") userId: UUID,
        @Param("createdAt") createdAt: LocalDateTime,
        @Param("id") id: UUID,
        limit: Limit,
    ): List<TableComment>

    @Query("SELECT c.postId as postId, COUNT(c) as count FROM TableComment c WHERE c.postId IN :postIds GROUP BY c.postId")
    fun countByPostIdIn(@Param("postIds") postIds: List<UUID>): List<PostCommentCount>

    fun existsByParentId(parentId: UUID): Boolean

    fun findByUserIdAndIdempotencyKey(userId: UUID, idempotencyKey: UUID): TableComment?

    // 같은 회원이 같은 대상에 같은 본문을 방금 썼는가(시간 창) - 톰스톤은 본문이 지워져 비교 대상이 아니다.
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM TableComment c " +
            "WHERE c.userId = :userId AND c.postId = :postId AND c.parentId IS NULL " +
            "AND c.content = :content AND c.isDeleted = false AND c.createdAt > :after",
    )
    fun existsRecentComment(
        @Param("userId") userId: UUID,
        @Param("postId") postId: UUID,
        @Param("content") content: String,
        @Param("after") after: LocalDateTime,
    ): Boolean

    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM TableComment c " +
            "WHERE c.userId = :userId AND c.parentId = :parentId " +
            "AND c.content = :content AND c.isDeleted = false AND c.createdAt > :after",
    )
    fun existsRecentReply(
        @Param("userId") userId: UUID,
        @Param("parentId") parentId: UUID,
        @Param("content") content: String,
        @Param("after") after: LocalDateTime,
    ): Boolean

    // 게시글 삭제 시 이미지 정리용 — 스칼라 프로젝션이라 TableComment가 영속성 컨텍스트에 올라가지 않는다.
    // 댓글이 글을 연관관계로 들던 때에는 엔티티로 로드하면 뒤따르는 글 삭제의 flush에서 TransientObjectException이
    // 났다(실제 배포 후 재현됨). 지금은 그 연관관계가 없지만 본문만 필요해 프로젝션을 그대로 쓴다.
    @Query("SELECT c.content FROM TableComment c WHERE c.postId = :postId")
    fun findAllContentByPostId(@Param("postId") postId: UUID): List<String>

    // 고아 이미지 정리 도구(OrphanImageCleanupRunner)용 — 위와 같은 이유로 스칼라 프로젝션을 쓴다.
    @Query("SELECT c.content FROM TableComment c")
    fun findAllContent(): List<String>

    // 이미지를 지우기 직전에 "다른 댓글이 아직 본문에 이 URL을 쓰는가"를 확인한다(CommentService
    // 삭제 경로). pattern은 호출부가 '!'로 이스케이프한 LIKE 패턴이다. 지우는 댓글 자신은 제외한다.
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM TableComment c " +
            "WHERE c.id <> :excludedId AND c.content LIKE :pattern ESCAPE '!'",
    )
    fun existsOtherCommentContaining(@Param("pattern") pattern: String, @Param("excludedId") excludedId: UUID): Boolean

    // 위와 같지만 게시글 삭제용 - 그 게시글의 댓글은 함께 지워지므로 전부 제외한다.
    @Query(
        "SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM TableComment c " +
            "WHERE c.postId <> :postId AND c.content LIKE :pattern ESCAPE '!'",
    )
    fun existsCommentOutsidePostContaining(@Param("pattern") pattern: String, @Param("postId") postId: UUID): Boolean
}
