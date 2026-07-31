package com.example.linksphere.infra.aws

import com.example.linksphere.domain.post.PostCreatedEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest

// AI 분석 작업을 이 Lambda 함수 자신에게 비동기(Event) 호출로 위임한다.
// 같은 실행 환경 안에서 스레드만 백그라운드로 돌리면 handleRequest() 반환 후
// 컨테이너가 얼어붙어 중단될 수 있다(과거 @Async 제거 사유). 완전히 별도의
// 실행 환경으로 넘기면 원래 요청의 응답 흐름과 무관해진다.
@Component
class AiJobDispatcher(
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(AiJobDispatcher::class.java)
    private val functionName: String? = System.getenv("AWS_LAMBDA_FUNCTION_NAME")

    private val lambdaClient: LambdaClient by lazy {
        LambdaClient.builder()
            .region(Region.of(System.getenv("AWS_REGION") ?: "ap-northeast-1"))
            .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
            .build()
    }

    fun dispatch(event: PostCreatedEvent) {
        val fnName = functionName
        if (fnName.isNullOrBlank()) {
            logger.warn("[AiJobDispatcher] AWS_LAMBDA_FUNCTION_NAME 없음 - AI 작업 발행 생략(로컬 환경으로 추정) - postId: ${event.postId}")
            return
        }

        val payload = objectMapper.writeValueAsString(AiJobPayload(event = event))
        val request =
            InvokeRequest.builder()
                .functionName(fnName)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(payload))
                .build()

        val response = lambdaClient.invoke(request)
        logger.info("[AiJobDispatcher] AI 작업 발행 - postId: ${event.postId}, statusCode: ${response.statusCode()}")
    }
}

// linksphereJob 필드로 LambdaHandler가 일반 HTTP 이벤트와 구분한다.
data class AiJobPayload(
    val linksphereJob: String = "ai-analysis",
    val event: PostCreatedEvent,
)
