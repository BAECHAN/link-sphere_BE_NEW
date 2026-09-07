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

    @Modifying
    @Query("UPDATE TablePost p SET p.viewCount = COALESCE(p.viewCount, 0) + 1 WHERE p.id = :id")
    fun incrementViewCount(@Param("id") id: UUID)
}
