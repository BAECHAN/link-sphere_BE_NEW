package com.example.linksphere.infra.aws

import com.example.linksphere.domain.comment.CommentPostProcessEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// 댓글 후처리(알림 발송 + 링크 프리뷰 크롤링)를 이 Lambda 함수 자신에게 비동기(Event) 호출로 위임한다.
// AiJobDispatcher와 동일한 이유(SnapStart freeze) - LambdaSelfInvoker 참고.
@Component
class CommentJobDispatcher(
    private val lambdaSelfInvoker: LambdaSelfInvoker,
) {
    private val logger = LoggerFactory.getLogger(CommentJobDispatcher::class.java)

    fun dispatch(event: CommentPostProcessEvent) {
        val dispatched = lambdaSelfInvoker.invoke(CommentJobPayload(event = event), "commentId: ${event.commentId}")
        if (!dispatched) {
            logger.warn("[CommentJobDispatcher] 댓글 후처리 작업 발행 생략(로컬 환경으로 추정) - commentId: ${event.commentId}")
        }
    }
}

// linksphereJob 필드로 LambdaHandler가 일반 HTTP 이벤트와 구분한다.
data class CommentJobPayload(
    val linksphereJob: String = "comment-postprocess",
    val event: CommentPostProcessEvent,
)
