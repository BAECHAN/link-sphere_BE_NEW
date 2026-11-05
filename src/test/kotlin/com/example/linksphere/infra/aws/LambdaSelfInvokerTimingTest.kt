package com.example.linksphere.infra.aws

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.metrics.CoreMetric
import software.amazon.awssdk.metrics.MetricCollector
import java.time.Duration

// 실제 invoke는 AWS가 필요해 형식 함수만 검증한다. 호출·시도 어느 쪽에 실리는 값이든 빠지면 배포 뒤에야
// 구간이 비어 보이므로, 2.57.4에서 실제로 실리는 위치 그대로 넣어 확인한다.
class LambdaSelfInvokerTimingTest {

    @Test
    fun `호출과 시도에 실린 구간 시간을 모두 읽는다`() {
        val call = MetricCollector.create("ApiCall")
        call.reportMetric(CoreMetric.API_CALL_DURATION, Duration.ofMillis(1500))
        call.reportMetric(CoreMetric.CREDENTIALS_FETCH_DURATION, Duration.ofMillis(300))
        call.reportMetric(CoreMetric.ENDPOINT_RESOLVE_DURATION, Duration.ofMillis(20))
        call.reportMetric(CoreMetric.MARSHALLING_DURATION, Duration.ofMillis(10))
        call.reportMetric(CoreMetric.RETRY_COUNT, 0)

        val attempt = call.createChild("ApiCallAttempt")
        attempt.reportMetric(CoreMetric.SIGNING_DURATION, Duration.ofMillis(5))
        attempt.reportMetric(CoreMetric.SERVICE_CALL_DURATION, Duration.ofMillis(900))
        attempt.reportMetric(CoreMetric.UNMARSHALLING_DURATION, Duration.ofMillis(2))

        assertEquals(
            "apiMs=1500, credentialsMs=300, endpointMs=20, marshallingMs=10, " +
                "signingMs=5, serviceCallMs=900, unmarshallingMs=2, retries=0",
            formatSdkTimings(call.collect()),
        )
    }

    @Test
    fun `재시도가 있으면 시도별 시간을 더한다`() {
        val call = MetricCollector.create("ApiCall")
        call.reportMetric(CoreMetric.RETRY_COUNT, 1)
        call.createChild("ApiCallAttempt").reportMetric(CoreMetric.SERVICE_CALL_DURATION, Duration.ofMillis(100))
        call.createChild("ApiCallAttempt").reportMetric(CoreMetric.SERVICE_CALL_DURATION, Duration.ofMillis(40))

        assertEquals(
            "apiMs=0, credentialsMs=0, endpointMs=0, marshallingMs=0, " +
                "signingMs=0, serviceCallMs=140, unmarshallingMs=0, retries=1",
            formatSdkTimings(call.collect()),
        )
    }
}
