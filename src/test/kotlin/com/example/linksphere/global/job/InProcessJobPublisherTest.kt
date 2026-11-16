package com.example.linksphere.global.job

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// 개발 환경의 뒷일 전달이 운영과 같은 모양({linksphereJob, event} JSON)으로 처리기에 닿는지 본다.
class InProcessJobPublisherTest {

    private data class SampleEvent(val id: UUID, val tags: List<String>)

    private class RecordingHandler(override val jobName: String, private val onHandle: (JsonNode) -> Unit = {}) : JobHandler {
        val received = CopyOnWriteArrayList<JsonNode>()
        val threads = CopyOnWriteArrayList<String>()
        val done = CountDownLatch(1)

        override fun handle(job: JsonNode) {
            received.add(job)
            threads.add(Thread.currentThread().name)
            try {
                onHandle(job)
            } finally {
                done.countDown()
            }
        }
    }

    private val mapper = jacksonObjectMapper()
    private var publisher: InProcessJobPublisher? = null

    private fun runner(vararg handlers: JobHandler): JobRunner {
        val beans = StaticListableBeanFactory()
        handlers.forEachIndexed { index, handler -> beans.addBean("handler$index", handler) }

        return JobRunner(beans.getBeanProvider(JobHandler::class.java)).also { it.afterSingletonsInstantiated() }
    }

    private fun publisher(runner: JobRunner) = InProcessJobPublisher(mapper, runner).also { publisher = it }

    @AfterEach
    fun tearDown() {
        publisher?.destroy()
    }

    @Test
    fun `맡긴 뒷일은 JSON을 거쳐 이름이 맞는 처리기에 닿는다`() {
        val target = RecordingHandler("sample-job")
        val other = RecordingHandler("other-job")
        val event = SampleEvent(UUID.randomUUID(), listOf("a", "b"))

        publisher(runner(target, other)).publish("sample-job", event, "test")

        assertTrue(target.done.await(5, TimeUnit.SECONDS))
        val job = target.received.single()
        assertEquals("sample-job", job.get("linksphereJob").asText())
        assertEquals(event, mapper.treeToValue(job.get("event"), SampleEvent::class.java))
        assertTrue(other.received.isEmpty())
    }

    @Test
    fun `처리기는 맡긴 스레드가 아닌 전용 스레드에서 돈다`() {
        val handler = RecordingHandler("sample-job")

        publisher(runner(handler)).publish("sample-job", SampleEvent(UUID.randomUUID(), emptyList()), "test")

        assertTrue(handler.done.await(5, TimeUnit.SECONDS))
        assertEquals("job-in-process", handler.threads.single())
        assertNotEquals(Thread.currentThread().name, handler.threads.single())
    }

    // 피드 항목 잡이 글을 만들면 그 안에서 AI 잡을 다시 맡긴다(FeedItemProcessor → PostService.createPost)
    @Test
    fun `처리기 안에서 다시 맡긴 뒷일도 처리된다`() {
        val inner = RecordingHandler("inner-job")
        lateinit var jobPublisher: InProcessJobPublisher
        val outer = RecordingHandler("outer-job") { jobPublisher.publish("inner-job", SampleEvent(UUID.randomUUID(), emptyList()), "inner") }
        jobPublisher = publisher(runner(outer, inner))

        jobPublisher.publish("outer-job", SampleEvent(UUID.randomUUID(), emptyList()), "outer")

        assertTrue(inner.done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `처리기가 실패해도 다음 뒷일은 처리된다`() {
        val failing = RecordingHandler("failing-job") { throw IllegalStateException("boom") }
        val next = RecordingHandler("next-job")
        val jobPublisher = publisher(runner(failing, next))

        jobPublisher.publish("failing-job", SampleEvent(UUID.randomUUID(), emptyList()), "first")
        jobPublisher.publish("next-job", SampleEvent(UUID.randomUUID(), emptyList()), "second")

        assertTrue(next.done.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `잡 이벤트가 아니거나 모르는 이름이면 처리하지 않는다`() {
        val handler = RecordingHandler("sample-job")
        val runner = runner(handler)

        assertFalse(runner.run(mapper.readTree("""{"rawPath":"/api/post"}""")))
        assertFalse(runner.run(mapper.readTree("""{"linksphereJob":"unknown-job"}""")))
        assertTrue(runner.run(mapper.readTree("""{"linksphereJob":"sample-job"}""")))
        assertEquals(1, handler.received.size)
    }

    @Test
    fun `잡 이름이 겹치면 시작할 때 실패한다`() {
        assertThrows(IllegalStateException::class.java) { runner(RecordingHandler("sample-job"), RecordingHandler("sample-job")) }
    }
}
