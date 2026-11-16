package com.example.linksphere.domain.feed

import com.example.linksphere.global.job.JobHandler
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component

// EventBridge cron이 직접 호출하는 Stage A 진입점(맡겨진 뒷일이 아니라 외부 트리거).
// 피드 소스를 순회해 후보 URL만 모으고, 실제 게시글 생성은 Stage B(feed-item)로 넘긴다.
@Component
class FeedCrawlJobHandler(
    private val feedCrawlService: FeedCrawlService,
) : JobHandler {

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        feedCrawlService.collectAndDispatch()
    }

    companion object {
        const val JOB_NAME = "feed-crawl"
    }
}
