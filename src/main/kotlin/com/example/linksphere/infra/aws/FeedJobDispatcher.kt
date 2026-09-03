package com.example.linksphere.infra.aws

import com.example.linksphere.domain.feed.FeedItemJobEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// FeedCrawlService(Stage A)가 모은 피드 항목 chunk를 이 Lambda 함수 자신에게 비동기(Event) 호출로
// 위임한다. AiJobDispatcher와 동일한 이유(SnapStart freeze) - LambdaSelfInvoker 참고.
@Component
class FeedJobDispatcher(
    private val lambdaSelfInvoker: LambdaSelfInvoker,
) {
    private val logger = LoggerFactory.getLogger(FeedJobDispatcher::class.java)

    fun dispatch(event: FeedItemJobEvent) {
        val dispatched =
            lambdaSelfInvoker.invoke(FeedItemJobPayload(event = event), "items: ${event.items.size}")
        if (!dispatched) {
            logger.warn("[FeedJobDispatcher] 피드 항목 작업 발행 생략(로컬 환경으로 추정) - items: ${event.items.size}")
        }
    }
}

// linksphereJob 필드로 LambdaHandler가 일반 HTTP 이벤트와 구분한다.
data class FeedItemJobPayload(
    val linksphereJob: String = "feed-item",
    val event: FeedItemJobEvent,
)
