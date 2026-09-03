package com.example.linksphere.domain.feed

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 관리자 API 없이 SQL 시딩(sql/create_feed_sources.sql)으로만 관리한다 — 이 코드베이스에
// admin/role 개념이 없어(tools/OrphanImageCleanupRunner.kt 참고) REST로 노출하면 로그인한
// 아무나 임의 URL을 서버가 크롤링하게 만드는 SSRF 게이트가 된다.
@Entity
@Table(name = "feed_sources")
class TableFeedSource(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID,
    @Column(name = "name", nullable = false, length = 100) val name: String,
    @Column(name = "url", nullable = false, columnDefinition = "text") val url: String,
    @Column(name = "enabled", nullable = false) var enabled: Boolean = true,
    @Column(name = "last_fetched_at") var lastFetchedAt: LocalDateTime? = null,
    @Column(name = "last_error", columnDefinition = "text") var lastError: String? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
)
