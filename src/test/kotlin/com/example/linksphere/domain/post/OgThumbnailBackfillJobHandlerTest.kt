package com.example.linksphere.domain.post

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

// 사용자가 CloudShell에서 보내는 입력(docs/DEPLOY.md "og 이미지 사본 소급")을 읽는 규칙을 본다
class OgThumbnailBackfillJobHandlerTest {

    private val mapper = jacksonObjectMapper()
    private val backfillService = mock(OgThumbnailBackfillService::class.java)
    private val handler = OgThumbnailBackfillJobHandler(backfillService)

    private val cursor = UUID.fromString("00000000-0000-4000-8000-000000000009")
    private val emptySummary = OgThumbnailBackfillSummary(0, 0, emptyMap(), 0L, null, false)

    @Test
    fun `limit을 빼면 1건만 처리한다`() {
        `when`(backfillService.run(1, null)).thenReturn(emptySummary)

        handler.handle(mapper.readTree("""{"linksphereJob":"og-thumbnail-backfill"}"""))

        verify(backfillService).run(1, null)
    }

    @Test
    fun `limit과 cursor를 최상위 필드로 받는다`() {
        `when`(backfillService.run(50, cursor)).thenReturn(emptySummary)

        handler.handle(mapper.readTree("""{"linksphereJob":"og-thumbnail-backfill","limit":50,"cursor":"$cursor"}"""))

        verify(backfillService).run(50, cursor)
    }

    @Test
    fun `limit이 1보다 작으면 1건으로 본다`() {
        `when`(backfillService.run(1, null)).thenReturn(emptySummary)

        handler.handle(mapper.readTree("""{"linksphereJob":"og-thumbnail-backfill","limit":0}"""))

        verify(backfillService).run(1, null)
    }
}
