package com.example.linksphere

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FunctionUrlOriginGuard.isAllowed()를 직접 검증한다. LambdaHandler의 companion object
 * init(전체 Spring Boot 부팅)을 거치지 않고도 순수 판정 로직만 빠르게 검증할 수 있다
 * (LambdaHandlerMultipartParsingTest와 동일한 이유).
 */
class FunctionUrlOriginGuardTest {

    private val secret = "test-secret"

    @Test
    fun `domainName이 없으면(EventBridge 워밍 핑·CI 게이트) 헤더 없이도 통과시킨다`() {
        assertTrue(FunctionUrlOriginGuard.isAllowed(domainName = null, headerValue = null, secret = secret))
        assertTrue(FunctionUrlOriginGuard.isAllowed(domainName = "", headerValue = null, secret = secret))
    }

    @Test
    fun `domainName이 lambda-url 이 아니면 통과시킨다`() {
        assertTrue(
            FunctionUrlOriginGuard.isAllowed(domainName = "example.com", headerValue = null, secret = secret),
        )
    }

    @Test
    fun `secret이 설정 안 됐으면(배포 과도기) 헤더 없이도 통과시킨다`() {
        assertTrue(
            FunctionUrlOriginGuard.isAllowed(
                domainName = "abc123.lambda-url.ap-northeast-1.on.aws",
                headerValue = null,
                secret = null,
            ),
        )
        assertTrue(
            FunctionUrlOriginGuard.isAllowed(
                domainName = "abc123.lambda-url.ap-northeast-1.on.aws",
                headerValue = null,
                secret = "",
            ),
        )
    }

    @Test
    fun `실제 Function URL 도메인인데 헤더가 없으면 막는다`() {
        assertFalse(
            FunctionUrlOriginGuard.isAllowed(
                domainName = "abc123.lambda-url.ap-northeast-1.on.aws",
                headerValue = null,
                secret = secret,
            ),
        )
    }

    @Test
    fun `헤더 값이 secret과 다르면 막는다`() {
        assertFalse(
            FunctionUrlOriginGuard.isAllowed(
                domainName = "abc123.lambda-url.ap-northeast-1.on.aws",
                headerValue = "wrong-secret",
                secret = secret,
            ),
        )
    }

    @Test
    fun `헤더 값이 secret과 같으면 통과시킨다`() {
        assertTrue(
            FunctionUrlOriginGuard.isAllowed(
                domainName = "abc123.lambda-url.ap-northeast-1.on.aws",
                headerValue = secret,
                secret = secret,
            ),
        )
    }
}
