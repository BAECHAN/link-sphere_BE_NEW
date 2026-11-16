package com.example.linksphere.domain.post

import com.example.linksphere.global.job.JobHandler
import com.example.linksphere.global.job.JobMessage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Component

// PostAIService.handlePostCreatedEvent가 맡긴 AI 분석을 처리한다. 원래 요청의 응답 흐름과 떨어져 돌아 오래 걸려도 된다.
@Component
class AiAnalysisJobHandler(
    private val objectMapper: ObjectMapper,
    private val postAIService: PostAIService,
) : JobHandler {

    private val logger = LoggerFactory.getLogger(AiAnalysisJobHandler::class.java)

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        val event = objectMapper.treeToValue(job.get(JobMessage.EVENT_FIELD), PostCreatedEvent::class.java)

        try {
            postAIService.processAiJob(event)
        } catch (e: ObjectOptimisticLockingFailureException) {
            // AI 분석 도중 post가 삭제된 정상적인 레이스 — processAiJob이 로그를 남기고
            // 트랜잭션을 정상 롤백한 뒤 다시 던진 것이다. 여기서 삼켜야 Lambda가 이 호출을
            // 실패로 보고 불필요하게 재시도하지 않는다.
            logger.info("[AI Job] 처리 중 post가 삭제됨 - postId: ${event.postId}")
        }
    }

    companion object {
        const val JOB_NAME = "ai-analysis"
    }
}
