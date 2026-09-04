package com.example.linksphere.domain.feed

import java.util.UUID

/** Stage A가 모아 Stage B로 self-invoke하는 항목 하나(아직 posts에 저장되기 전). */
data class FeedCrawlItem(
    val sourceId: UUID,
    val title: String,
    val url: String,
    // 크롤링이 403 등으로 막혔을 때 쓸 RSS 본문(FeedParser에서 이미 정규화·5000자 절단 완료).
    // 크롤링이 성공하면 쓰이지 않는다 - PostService.createPost 참고.
    val content: String? = null,
)

/** Stage A → Stage B self-invoke payload의 event 필드. */
data class FeedItemJobEvent(
    val botId: UUID,
    val items: List<FeedCrawlItem>,
)
