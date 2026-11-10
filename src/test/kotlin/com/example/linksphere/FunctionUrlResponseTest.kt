package com.example.linksphere

import com.amazonaws.serverless.proxy.model.AwsProxyResponse
import com.amazonaws.serverless.proxy.model.Headers
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * serverless-java-container 응답을 Function URL 응답 페이로드로 바꾸는 규칙을 고정한다. 예전 직렬화는 헤더 이름마다 첫 값만
 * 실어 CORS가 붙이는 Vary 세 값 중 Origin만 나갔고, Set-Cookie가 둘이면 하나가 조용히 사라질 구조였다(2026-10-26).
 * 라이브러리 응답에는 cookies 필드가 없어 Set-Cookie를 여기서 옮기지 않으면 로그인이 유지되지 않는다.
 */
class FunctionUrlResponseTest {

    @Test
    fun `Set-Cookie는 headers가 아니라 cookies 배열로 하나씩 보낸다`() {
        val headers = Headers()
        headers.add(HttpHeaders.SET_COOKIE, "__Host-refreshToken=a; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
        headers.add(HttpHeaders.SET_COOKIE, "other=b; Path=/")

        val payload = FunctionUrlResponse.from(AwsProxyResponse(200, headers, ""))

        assertEquals(
            listOf("__Host-refreshToken=a; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT", "other=b; Path=/"),
            payload["cookies"],
        )
        @Suppress("UNCHECKED_CAST")
        val payloadHeaders = payload["headers"] as Map<String, String>
        assertFalse(payloadHeaders.keys.any { it.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) })
    }

    @Test
    fun `값이 여럿인 헤더는 콤마로 합치고 하나인 헤더는 그대로 둔다`() {
        val headers = Headers()
        headers.add(HttpHeaders.VARY, "Origin")
        headers.add(HttpHeaders.VARY, "Access-Control-Request-Method")
        headers.add(HttpHeaders.VARY, "Access-Control-Request-Headers")
        headers.add(HttpHeaders.CACHE_CONTROL, "no-store")

        @Suppress("UNCHECKED_CAST")
        val payloadHeaders = FunctionUrlResponse.from(AwsProxyResponse(200, headers, ""))["headers"] as Map<String, String>

        assertEquals("Origin, Access-Control-Request-Method, Access-Control-Request-Headers", payloadHeaders[HttpHeaders.VARY])
        assertEquals("no-store", payloadHeaders[HttpHeaders.CACHE_CONTROL])
    }

    @Test
    fun `쿠키가 없으면 cookies 키를 싣지 않고 상태와 본문은 그대로 옮긴다`() {
        val headers = Headers()
        headers.add(HttpHeaders.CONTENT_TYPE, "application/json")

        val payload = FunctionUrlResponse.from(AwsProxyResponse(201, headers, """{"status":201}"""))

        assertFalse(payload.containsKey("cookies"))
        assertEquals(201, payload["statusCode"])
        assertEquals("""{"status":201}""", payload["body"])
        assertEquals(false, payload["isBase64Encoded"])
    }

    @Test
    fun `base64 본문 표시를 싣고 헤더·본문이 없으면 빈 값으로 보낸다`() {
        val response = AwsProxyResponse(200)
        response.isBase64Encoded = true

        val payload = FunctionUrlResponse.from(response)

        assertTrue(payload["isBase64Encoded"] as Boolean)
        assertEquals(emptyMap<String, String>(), payload["headers"])
        assertEquals("", payload["body"])
    }
}
