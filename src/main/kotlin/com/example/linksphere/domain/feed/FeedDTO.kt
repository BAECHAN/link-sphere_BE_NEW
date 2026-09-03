package com.example.linksphere.domain.feed

import java.util.UUID

/** Stage A가 모아 Stage B로 self-invoke하는 항목 하나(아직 posts에 저장되기 전). */
data class FeedCrawlItem(
    val sourceId: UUID,
    val title: String,
    val url: String,
)

/** Stage A → Stage B self-invoke payload의 event 필드. */
data class FeedItemJobEvent(
    val botId: UUID,
    val items: List<FeedCrawlItem>,
)
