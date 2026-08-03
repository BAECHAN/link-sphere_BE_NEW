package com.example.linksphere.infra.aws

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest

// 이 Lambda 함수 자신에게 비동기(Event) 호출로 작업을 위임하는 공용 컴포넌트.
// 같은 실행 환경 안에서 스레드만 백그라운드로 돌리면 handleRequest() 반환 후
// 컨테이너가 얼어붙어 중단될 수 있다(과거 @Async 제거 사유). 완전히 별도의
// 실행 환경으로 넘기면 원래 요청의 응답 흐름과 무관해진다.
@Component
class LambdaSelfInvoker(
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(LambdaSelfInvoker::class.java)
    private val functionName: String? = System.getenv("AWS_LAMBDA_FUNCTION_NAME")

    // qualifier를 안 주면 AWS가 $LATEST로 호출하는데, SnapStart는 ApplyOn=PublishedVersions라
    // $LATEST엔 스냅샷 최적화가 적용되지 않아 매번 완전 콜드스타트를 물게 된다.
    // EventBridge 워밍 핑과 동일한 이유로 반드시 prod alias를 명시해야 한다 (docs/DEPLOY.md 6장).
    private companion object {
        const val PROD_QUALIFIER = "prod"
    }

    private val lambdaClient: LambdaClient by lazy {
        LambdaClient.builder()
            .region(Region.of(System.getenv("AWS_REGION") ?: "ap-northeast-1"))
            .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
            .build()
    }

    /** [payload]를 이 함수 자신에게 Event(비동기) 호출로 위임한다. 로컬 등 함수명이 없는 환경에서는 스킵. */
    fun invoke(payload: Any, logContext: String): Boolean {
        val fnName = functionName
        if (fnName.isNullOrBlank()) {
            logger.warn("[LambdaSelfInvoker] AWS_LAMBDA_FUNCTION_NAME 없음 - 위임 생략(로컬 환경으로 추정) - $logContext")
            return false
        }

        val body = objectMapper.writeValueAsString(payload)
        val request =
            InvokeRequest.builder()
                .functionName(fnName)
                .qualifier(PROD_QUALIFIER)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(body))
                .build()

        val response = lambdaClient.invoke(request)
        logger.info("[LambdaSelfInvoker] 작업 발행 - $logContext, statusCode: ${response.statusCode()}")
        return true
    }
}
