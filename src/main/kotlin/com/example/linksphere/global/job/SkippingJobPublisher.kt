package com.example.linksphere.global.job

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 뒷일을 맡기지 않고 버린다. 로컬 1회성 도구(`tools/`의 Runner)의 기본값이다 — 도구가 글을 만들 때마다 Gemini 호출과
 * 크롤링이 따라 돌지 않게 한다. 이렇게 등록한 글은 aiStatus가 PENDING에 남는다(docs/AI-ASYNC-PROCESSING.md).
 */
@Component
@ConditionalOnProperty(name = ["app.job.publisher"], havingValue = "skip")
class SkippingJobPublisher : JobPublisher {

    private val logger = LoggerFactory.getLogger(SkippingJobPublisher::class.java)

    override fun publish(jobName: String, event: Any, logContext: String) {
        logger.warn("[SkippingJobPublisher] 작업 발행 생략(app.job.publisher=skip) - $jobName, $logContext")
    }
}
