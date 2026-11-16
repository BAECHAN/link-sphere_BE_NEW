package com.example.linksphere.global.job

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/**
 * 개발 환경용 — 뒷일을 같은 프로세스의 전용 스레드에서 처리한다. 운영(Lambda)에서는 쓰지 않는다: 응답을 돌려준 뒤
 * 실행 환경이 얼어 스레드가 멈춘다(docs/AI-ASYNC-PROCESSING.md).
 *
 * - `AsyncConfig`의 풀을 쓰지 않는다. AI 잡은 그 풀에 하위 작업 둘을 맡기고 기다리는데(`PostAIService.processAiJob`),
 *   잡 자신이 같은 풀의 스레드를 차지하면 잡이 둘만 겹쳐도 하위 작업이 돌 스레드가 없어 서로 기다린다.
 * - 운영과 같이 JSON으로 바꿨다가 되돌려 처리기에 넘긴다. 직렬화가 안 되는 이벤트가 개발 환경에서 드러난다.
 */
@Component
@ConditionalOnProperty(name = ["app.job.publisher"], havingValue = "in-process")
class InProcessJobPublisher(
    private val objectMapper: ObjectMapper,
    private val jobRunner: JobRunner,
) : JobPublisher,
    DisposableBean {

    private val logger = LoggerFactory.getLogger(InProcessJobPublisher::class.java)

    private val executor =
        Executors.newFixedThreadPool(THREADS) { task -> Thread(task, "job-in-process").apply { isDaemon = true } }

    override fun publish(jobName: String, event: Any, logContext: String) {
        val body = objectMapper.writeValueAsString(JobMessage.of(jobName, event))
        logger.info("[InProcessJobPublisher] 작업 발행 - $jobName, $logContext")

        executor.execute {
            runCatching { jobRunner.run(objectMapper.readTree(body)) }
                .onSuccess { handled -> if (!handled) logger.error("[InProcessJobPublisher] 처리기 없음 - $jobName, $logContext") }
                .onFailure { e -> logger.error("[InProcessJobPublisher] 작업 실패 - $jobName, $logContext", e) }
        }
    }

    override fun destroy() {
        executor.shutdown()
    }

    private companion object {
        const val THREADS = 2
    }
}
