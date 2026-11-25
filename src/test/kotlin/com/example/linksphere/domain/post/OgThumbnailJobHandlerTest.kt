package com.example.linksphere.domain.post

import com.example.linksphere.global.job.JobMessage
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.UUID

class OgThumbnailJobHandlerTest {

    private val mapper = jacksonObjectMapper()
    private val ogThumbnailService = mock(OgThumbnailService::class.java)
    private val handler = OgThumbnailJobHandler(mapper, ogThumbnailService)

    private val postId = UUID.fromString("00000000-0000-4000-8000-000000000001")

    // 리스너가 맡기는 JSON 모양 그대로다(JobMessage.of). 배포 순간 떠 있는 호출이 이 모양으로 온다
    @Test
    fun `맡긴 JSON의 event를 OgThumbnailJobEvent로 되돌려 사본을 만든다`() {
        val event = OgThumbnailJobEvent(postId, "https://example.com/og.png")
        val json = mapper.writeValueAsString(JobMessage.of(OgThumbnailJobHandler.JOB_NAME, event))
        `when`(ogThumbnailService.createThumbnail(postId, "https://example.com/og.png")).thenReturn(OgThumbnailResult.NotStored("not-image"))

        handler.handle(mapper.readTree(json))

        assertEquals("""{"linksphereJob":"og-thumbnail","event":{"postId":"$postId","ogImage":"https://example.com/og.png"}}""", json)
        verify(ogThumbnailService).createThumbnail(postId, "https://example.com/og.png")
    }
}
