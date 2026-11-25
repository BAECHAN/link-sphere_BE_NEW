package com.example.linksphere.domain.post

import com.example.linksphere.global.job.JobHandler
import com.example.linksphere.global.job.JobMessage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

// OgThumbnailService.handleOgImageChanged가 커밋 뒤 맡긴 사본 하나를 만든다. 글 등록 응답과 떨어져 돈다(원본 받기에 최대 10초).
@Component
class OgThumbnailJobHandler(
    private val objectMapper: ObjectMapper,
    private val ogThumbnailService: OgThumbnailService,
) : JobHandler {

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        val event = objectMapper.treeToValue(job.get(JobMessage.EVENT_FIELD), OgThumbnailJobEvent::class.java)
        ogThumbnailService.createThumbnail(event.postId, event.ogImage)
    }

    companion object {
        const val JOB_NAME = "og-thumbnail"
    }
}
