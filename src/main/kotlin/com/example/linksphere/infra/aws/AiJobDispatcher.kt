package com.example.linksphere.infra.aws

import com.example.linksphere.domain.post.PostCreatedEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// AI 분석 작업을 이 Lambda 함수 자신에게 비동기(Event) 호출로 위임한다.
// 같은 실행 환경 안에서 스레드만 백그라운드로 돌리면 handleRequest() 반환 후
// 컨테이너가 얼어붙어 중단될 수 있다(과거 @Async 제거 사유). 완전히 별도의
// 실행 환경으로 넘기면 원래 요청의 응답 흐름과 무관해진다.
@Component
class AiJobDispatcher(
    private val lambdaSelfInvoker: LambdaSelfInvoker,
) {
    private val logger = LoggerFactory.getLogger(AiJobDispatcher::class.java)

    fun dispatch(event: PostCreatedEvent) {
        val dispatched = lambdaSelfInvoker.invoke(AiJobPayload(event = event), "postId: ${event.postId}")
        if (!dispatched) {
            logger.warn("[AiJobDispatcher] AI 작업 발행 생략(로컬 환경으로 추정) - postId: ${event.postId}")
        }
    }
}

// linksphereJob 필드로 LambdaHandler가 일반 HTTP 이벤트와 구분한다.
data class AiJobPayload(
    val linksphereJob: String = "ai-analysis",
    val event: PostCreatedEvent,
)
