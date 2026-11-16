package com.example.linksphere.domain.post

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.util.UUID

// LambdaHandler.handleAiJob에 있던 삭제 경합 처리가 처리기로 옮겨진 뒤에도 같은지 본다.
class AiAnalysisJobHandlerTest {

    private val mapper = jacksonObjectMapper()
    private val postAIService = mock(PostAIService::class.java)
    private val handler = AiAnalysisJobHandler(mapper, postAIService)

    private val event = PostCreatedEvent(UUID.randomUUID(), UUID.randomUUID(), "제목", null, "본문", listOf("a"))
    private val job = mapper.readTree(mapper.writeValueAsString(mapOf("linksphereJob" to "ai-analysis", "event" to event)))

    @Test
    fun `event 필드를 PostCreatedEvent로 되돌려 처리한다`() {
        handler.handle(job)

        verify(postAIService).processAiJob(event)
    }

    // 삼키지 않으면 Lambda가 이 호출을 실패로 보고 다시 시도한다
    @Test
    fun `분석 중 글이 지워진 경합은 삼킨다`() {
        doThrow(ObjectOptimisticLockingFailureException(TablePost::class.java, event.postId)).`when`(postAIService).processAiJob(event)

        assertDoesNotThrow { handler.handle(job) }
    }

    @Test
    fun `그 밖의 예외는 그대로 던진다`() {
        doThrow(IllegalStateException("boom")).`when`(postAIService).processAiJob(event)

        assertThrows(IllegalStateException::class.java) { handler.handle(job) }
    }
}
