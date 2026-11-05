package com.example.linksphere.infra.aws

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.metrics.MetricCollection
import software.amazon.awssdk.metrics.MetricPublisher
import software.amazon.awssdk.metrics.SdkMetric
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import java.time.Duration

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

    // 첫 발행 구간 로그용 상태(docs/PERFORMANCE.md 4.6). 실행 환경당 요청은 한 번에 하나라 동기화하지 않는다.
    private var firstPublishPending = true
    private var lastCallMetrics: MetricCollection? = null

    // publish에서 던진 예외는 SDK가 invoke 밖으로 그대로 던진다(2.57.4 직접 확인). 발행을 깨지 않게 값만 담는다.
    private val metricsHolder =
        object : MetricPublisher {
            override fun publish(metricCollection: MetricCollection) {
                lastCallMetrics = metricCollection
            }

            override fun close() = Unit
        }

    // 실제 클라이언트는 복원 뒤 첫 호출에서 만든다. SDK 초기화는 LambdaHandler.warmUpAwsSdk가 스냅샷 전에 해 둔다.
    private val lambdaClient: LambdaClient by lazy {
        LambdaClient.builder()
            .region(Region.of(System.getenv("AWS_REGION") ?: "ap-northeast-1"))
            .httpClientBuilder(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.builder())
            .overrideConfiguration { it.addMetricPublisher(metricsHolder) }
            .build()
    }

    /** [payload]를 이 함수 자신에게 Event(비동기) 호출로 위임한다. 로컬 등 함수명이 없는 환경에서는 스킵. */
    fun invoke(payload: Any, logContext: String): Boolean {
        val fnName = functionName
        if (fnName.isNullOrBlank()) {
            logger.warn("[LambdaSelfInvoker] AWS_LAMBDA_FUNCTION_NAME 없음 - 위임 생략(로컬 환경으로 추정) - $logContext")
            return false
        }

        // 실패한 첫 호출 뒤의 호출을 첫 발행으로 잘못 찍지 않게 호출 전에 내린다.
        val isFirstPublish = firstPublishPending
        firstPublishPending = false

        val startedAt = System.nanoTime()
        val body = objectMapper.writeValueAsString(payload)
        val bodyBuiltAt = System.nanoTime()
        val request =
            InvokeRequest.builder()
                .functionName(fnName)
                .qualifier(PROD_QUALIFIER)
                .invocationType(InvocationType.EVENT)
                .payload(SdkBytes.fromUtf8String(body))
                .build()
        val requestBuiltAt = System.nanoTime()
        val client = lambdaClient
        val clientReadyAt = System.nanoTime()

        val response = client.invoke(request)
        val finishedAt = System.nanoTime()
        logger.info("[LambdaSelfInvoker] 작업 발행 - $logContext, statusCode: ${response.statusCode()}")

        if (isFirstPublish) {
            logger.info(
                "[LambdaSelfInvoker] 첫 발행 구간 - $logContext, " +
                    "totalMs=${elapsedMillis(startedAt, finishedAt)}, " +
                    "jsonMs=${elapsedMillis(startedAt, bodyBuiltAt)}, " +
                    "requestMs=${elapsedMillis(bodyBuiltAt, requestBuiltAt)}, " +
                    "clientMs=${elapsedMillis(requestBuiltAt, clientReadyAt)}, " +
                    "${lastCallMetrics?.let { formatSdkTimings(it) }}",
            )
        }

        return true
    }

    private fun elapsedMillis(fromNanos: Long, toNanos: Long): Long = (toNanos - fromNanos) / 1_000_000
}

// 값이 호출(ApiCall)과 시도(ApiCallAttempt) 중 어디에 실리는지 가리지 않게 둘 다 더한다. 재시도가 있으면 시도별 값이 합쳐진다.
internal fun formatSdkTimings(metrics: MetricCollection): String {
    val collections = listOf(metrics) + metrics.children()

    fun millis(metric: SdkMetric<Duration>): Long = collections.flatMap { it.metricValues(metric) }.sumOf { it.toMillis() }

    return "apiMs=${millis(CoreMetric.API_CALL_DURATION)}, " +
        "credentialsMs=${millis(CoreMetric.CREDENTIALS_FETCH_DURATION)}, " +
        "endpointMs=${millis(CoreMetric.ENDPOINT_RESOLVE_DURATION)}, " +
        "marshallingMs=${millis(CoreMetric.MARSHALLING_DURATION)}, " +
        "signingMs=${millis(CoreMetric.SIGNING_DURATION)}, " +
        "serviceCallMs=${millis(CoreMetric.SERVICE_CALL_DURATION)}, " +
        "unmarshallingMs=${millis(CoreMetric.UNMARSHALLING_DURATION)}, " +
        "retries=${metrics.metricValues(CoreMetric.RETRY_COUNT).sum()}"
}
