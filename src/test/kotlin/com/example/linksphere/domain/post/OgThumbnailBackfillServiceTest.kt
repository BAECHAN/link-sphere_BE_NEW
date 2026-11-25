package com.example.linksphere.domain.post

import com.example.linksphere.global.common.BatchJobLimits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.domain.Limit
import java.util.UUID

// 소급 잡(og-thumbnail-backfill)이 건수 상한·90초 마감을 지키고, 다음 호출에 넘길 cursor와 남은 수를 요약 로그로 남기는지 본다
@ExtendWith(OutputCaptureExtension::class)
class OgThumbnailBackfillServiceTest {

    private val postRepository = mock(PostRepository::class.java)
    private val ogThumbnailService = mock(OgThumbnailService::class.java)
    private val service = OgThumbnailBackfillService(postRepository, ogThumbnailService)

    private val start = UUID(0L, 0L)
    private val first = candidate("00000000-0000-4000-8000-000000000001", "https://a.example/1.png")
    private val second = candidate("00000000-0000-4000-8000-000000000002", "https://b.example/2.webp")
    private val third = candidate("00000000-0000-4000-8000-000000000003", "https://a.example/3.png")

    @Test
    fun `건수 상한만큼 id 순으로 하나씩 만들고 실패를 이유·사이트별로 센 요약을 남긴다`(output: CapturedOutput) {
        `when`(postRepository.findOgThumbnailCandidatesAfter(start, Limit.of(3))).thenReturn(listOf(first, second, third))
        `when`(ogThumbnailService.createThumbnail(first.id, first.ogImage)).thenReturn(OgThumbnailResult.Stored("https://cdn.test/1.jpg"))
        `when`(ogThumbnailService.createThumbnail(second.id, second.ogImage)).thenReturn(OgThumbnailResult.NotStored("unsupported-format"))
        `when`(ogThumbnailService.createThumbnail(third.id, third.ogImage)).thenReturn(OgThumbnailResult.Stored("https://cdn.test/3.jpg"))
        `when`(postRepository.countOgThumbnailCandidatesAfter(third.id)).thenReturn(7L)

        val summary = service.run(limit = 3, cursor = null) { 0L }

        assertEquals(
            OgThumbnailBackfillSummary(
                processed = 3,
                stored = 2,
                failures = mapOf("unsupported-format@b.example" to 1),
                remaining = 7L,
                nextCursor = third.id,
                deadlineReached = false,
            ),
            summary,
        )
        assertTrue(
            output.out.contains(
                "[OgThumbnailBackfill] 완료 - processed=3, stored=2, failed=1, failures={unsupported-format@b.example=1}, " +
                    "remaining=7, nextCursor=${third.id}, deadlineReached=false",
            ),
            output.out,
        )
    }

    @Test
    fun `cursor를 주면 그 뒤의 글부터 읽는다`() {
        `when`(postRepository.findOgThumbnailCandidatesAfter(first.id, Limit.of(1))).thenReturn(listOf(second))
        `when`(ogThumbnailService.createThumbnail(second.id, second.ogImage)).thenReturn(OgThumbnailResult.Stored("https://cdn.test/2.jpg"))

        val summary = service.run(limit = 1, cursor = first.id) { 0L }

        assertEquals(second.id, summary.nextCursor)
        verify(ogThumbnailService, never()).createThumbnail(first.id, first.ogImage)
    }

    // 마감은 글 하나를 시작하기 전에 본다. 넘었으면 그 글부터 다음 호출로 넘긴다 - cursor는 마지막으로 처리한 글이다
    @Test
    fun `90초 마감이 지나면 남은 글을 다음 호출로 넘긴다`() {
        `when`(postRepository.findOgThumbnailCandidatesAfter(start, Limit.of(3))).thenReturn(listOf(first, second, third))
        `when`(ogThumbnailService.createThumbnail(first.id, first.ogImage)).thenReturn(OgThumbnailResult.Stored("https://cdn.test/1.jpg"))
        `when`(postRepository.countOgThumbnailCandidatesAfter(first.id)).thenReturn(2L)
        val clock = ArrayDeque(listOf(0L, 0L, BatchJobLimits.DEADLINE_MILLIS + 1))

        val summary = service.run(limit = 3, cursor = null) { clock.removeFirstOrNull() ?: Long.MAX_VALUE }

        assertEquals(1, summary.processed)
        assertEquals(first.id, summary.nextCursor)
        assertEquals(2L, summary.remaining)
        assertEquals(true, summary.deadlineReached)
        verify(ogThumbnailService, never()).createThumbnail(second.id, second.ogImage)
    }

    // 한 글의 예외가 나머지를 막지 않는다(AccountPurgeService 선례). 실패는 표시하지 않아 cursor 없이 다시 부르면 또 시도한다
    @Test
    fun `한 글이 예외로 실패해도 나머지를 계속하고 실패로 센다`() {
        `when`(postRepository.findOgThumbnailCandidatesAfter(start, Limit.of(2))).thenReturn(listOf(first, second))
        `when`(ogThumbnailService.createThumbnail(first.id, first.ogImage)).thenThrow(IllegalStateException("S3 down"))
        `when`(ogThumbnailService.createThumbnail(second.id, second.ogImage)).thenReturn(OgThumbnailResult.Stored("https://cdn.test/2.jpg"))

        val summary = service.run(limit = 2, cursor = null) { 0L }

        assertEquals(2, summary.processed)
        assertEquals(1, summary.stored)
        assertEquals(mapOf("exception-IllegalStateException@a.example" to 1), summary.failures)
    }

    @Test
    fun `남은 글이 없으면 cursor를 그대로 두고 남은 수 0을 남긴다`() {
        `when`(postRepository.findOgThumbnailCandidatesAfter(third.id, Limit.of(50))).thenReturn(emptyList())

        val summary = service.run(limit = 50, cursor = third.id) { 0L }

        assertEquals(OgThumbnailBackfillSummary(0, 0, emptyMap(), 0L, third.id, false), summary)
    }

    private fun candidate(id: String, ogImage: String): OgThumbnailCandidate = object : OgThumbnailCandidate {
        override val id: UUID = UUID.fromString(id)
        override val ogImage: String = ogImage
    }
}
