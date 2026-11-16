package com.example.linksphere.domain.comment

import com.example.linksphere.global.job.JobHandler
import com.example.linksphere.global.job.JobMessage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component

// CommentPostProcessService.handleCommentPostProcess가 맡긴 댓글 후처리(알림 발송 + 링크 프리뷰 크롤링)를 처리한다.
// 삭제 레이스는 processCommentJob이 조용히 return하므로 AiAnalysisJobHandler와 달리 별도 예외 처리가 필요 없다.
@Component
class CommentPostProcessJobHandler(
    private val objectMapper: ObjectMapper,
    private val commentPostProcessService: CommentPostProcessService,
) : JobHandler {

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        val event = objectMapper.treeToValue(job.get(JobMessage.EVENT_FIELD), CommentPostProcessEvent::class.java)
        commentPostProcessService.processCommentJob(event)
    }

    companion object {
        const val JOB_NAME = "comment-postprocess"
    }
}
