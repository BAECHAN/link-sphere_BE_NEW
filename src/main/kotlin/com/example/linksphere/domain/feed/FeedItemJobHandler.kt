package com.example.linksphere.domain.feed

import com.example.linksphere.global.job.JobHandler
import com.example.linksphere.global.job.JobMessage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

// FeedCrawlService.collectAndDispatch가 맡긴 Stage B 처리부. 항목 하나가 실패해도 나머지는 계속 처리하므로
// (FeedCrawlService.processFeedItemJob 내부의 runCatching) 여기서 별도 예외 처리는 필요 없다.
@Component
class FeedItemJobHandler(
    private val objectMapper: ObjectMapper,
    private val feedCrawlService: FeedCrawlService,
) : JobHandler {

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        val event = objectMapper.treeToValue(job.get(JobMessage.EVENT_FIELD), FeedItemJobEvent::class.java)
        feedCrawlService.processFeedItemJob(event)
    }

    companion object {
        const val JOB_NAME = "feed-item"
    }
}
