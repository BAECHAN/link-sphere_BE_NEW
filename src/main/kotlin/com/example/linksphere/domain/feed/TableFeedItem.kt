package com.example.linksphere.domain.feed

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime
import java.util.UUID

// 봇 게시글 중복 등록 방지 전용 원장. posts.url에는 unique를 걸지 않는다 — 사람 사용자가
// 같은 URL을 각자 등록하는 건 정상 동작이고, 기존 중복 데이터가 있으면 그 자체로 마이그레이션이
// 실패한다(FeedItemRepository 참고). postId는 nullable + ON DELETE SET NULL이라, 관리자가
// 봇 글을 지워도 이 행은 남아 같은 URL이 다음 날 재수집되지 않는다.
@Entity
@Table(name = "feed_items")
class TableFeedItem(
    @Id
    @Column(name = "id", nullable = false)
    val id: UUID,
    @Column(name = "source_id") val sourceId: UUID?,
    @Column(name = "post_id") var postId: UUID?,
    @Column(name = "normalized_url", nullable = false, columnDefinition = "text") val normalizedUrl: String,
    @Column(name = "created_at") val createdAt: LocalDateTime? = LocalDateTime.now(),
)
