package com.example.linksphere.domain.post

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

interface PostRepository :
    JpaRepository<TablePost, UUID>,
    PostRepositoryCustom {

    fun findByCategoriesSlug(slug: String): List<TablePost>

    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): Page<TablePost>

    fun findByCategoriesSlugOrderByCreatedAtDesc(slug: String, pageable: Pageable): Page<TablePost>

    fun findAllByUserIdAndAiSummaryIsNull(userId: UUID): List<TablePost>

    fun findAllByAiStatusInAndCreatedAtBefore(aiStatuses: List<AiStatus>, before: LocalDateTime): List<TablePost>

    // 크롤링이 "성공"했지만 실제로는 페이지 껍데기를 요약해 aiStatus=COMPLETED로 확정된 글은 위
    // 두 조회에 걸리지 않는다(요약도 있고 상태도 COMPLETED다). 도메인 단위로 통째로 다시 긁기
    // 위한 조회로, PostAiBackfillRunner의 --url-like 인자에서만 쓴다.
    fun findAllByUrlContainingIgnoreCase(fragment: String): List<TablePost>

    // 장애 조사 문서·PR 본문에는 개인정보 노출을 피하려 postId 앞 8자만 남기는 관례가 있다
    // (docs/AI-ASYNC-PROCESSING.md §5.9). PostLocaleBackfillRunner의 --post-id가 그 접두어만으로도
    // 대상을 찾을 수 있게 하는 조회. 이 레포에 @DataJpaTest 등 실 DB 대상 테스트 인프라가 없어
    // (CommentServiceTest.kt 참고) HQL의 uuid-string CAST 표현이 검증될 경로가 없으므로, 표준
    // Postgres 캐스트 문법을 쓰는 네이티브 쿼리로 위험을 줄인다.
    @Query(value = "SELECT * FROM posts WHERE id::text LIKE CONCAT(:prefix, '%')", nativeQuery = true)
    fun findAllByIdStartingWith(@Param("prefix") prefix: String): List<TablePost>

    @Modifying
    @Query("UPDATE TablePost p SET p.viewCount = COALESCE(p.viewCount, 0) + 1 WHERE p.id = :id")
    fun incrementViewCount(@Param("id") id: UUID)

    // embedding은 TablePost에 insertable/updatable=false로 매핑돼 있어 JPA save()로는
    // 못 쓴다 - 항상 이 네이티브 UPDATE로만 쓴다. embeddingLiteral은 "[0.1,0.2,...]" 형식
    // 문자열이나 null(리셋용)이고, CAST가 Postgres의 vector 입력 파싱을 그대로 쓴다.
    @Modifying
    @Query(value = "UPDATE posts SET embedding = CAST(:embeddingLiteral AS vector) WHERE id = :id", nativeQuery = true)
    fun updateEmbedding(@Param("id") id: UUID, @Param("embeddingLiteral") embeddingLiteral: String?)

    // 백필 대상: 임베딩이 없는 글 중 1시간 이상 지난 것만 - PostAiBackfillRunner와 동일한
    // 이유로, 방금 등록돼 AI 잡이 진행 중인 글을 동시에 건드리지 않기 위함이다.
    @Query(value = "SELECT * FROM posts WHERE embedding IS NULL AND created_at < :before ORDER BY created_at", nativeQuery = true)
    fun findAllWithoutEmbeddingCreatedBefore(@Param("before") before: LocalDateTime): List<TablePost>
}
