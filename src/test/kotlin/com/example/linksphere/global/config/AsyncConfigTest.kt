package com.example.linksphere.global.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * @Async 풀 스레드가 호출한 쪽의 MDC(LambdaHandler의 awsRequestId)를 넘겨받고, 작업이 끝나면 비워 다음 작업에 새지 않는지 본다.
 */
class AsyncConfigTest {

    private val executor = AsyncConfig().asyncExecutor as ThreadPoolTaskExecutor

    @AfterEach
    fun tearDown() {
        MDC.clear()
        executor.shutdown()
    }

    @Test
    fun `제출한 쪽의 MDC가 작업 스레드에 보이고 끝나면 비워진다`() {
        // 두 작업이 같은 워커 스레드에서 돌아야 "끝나면 비운다"를 검증할 수 있다 - 기본 core 2개면 두 번째 작업이 새 워커에서
        // 돌아 clear가 없어도 통과한다
        executor.corePoolSize = 1
        executor.maxPoolSize = 1

        MDC.put("awsRequestId", "req-1")
        val first = executor.submit<Pair<String, String?>> { Thread.currentThread().name to MDC.get("awsRequestId") }.get(5, TimeUnit.SECONDS)

        MDC.clear()
        // 같은 워커의 다음 작업(제출한 쪽 MDC 없음)에는 앞 요청의 ID가 남지 않아야 한다
        val second = executor.submit<Pair<String, String?>> { Thread.currentThread().name to MDC.get("awsRequestId") }.get(5, TimeUnit.SECONDS)

        assertEquals("req-1", first.second)
        assertEquals(first.first, second.first)
        assertNull(second.second)
    }
}
