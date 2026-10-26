package com.example.linksphere

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * MockMvc 응답을 Function URL 응답 페이로드로 바꾸는 규칙을 고정한다. 예전 직렬화는 헤더 이름마다 첫 값만 실어 CORS가
 * 붙이는 Vary 세 값 중 Origin만 나갔고, Set-Cookie가 둘이면 하나가 조용히 사라질 구조였다(2026-10-26).
 */
class FunctionUrlResponseTest {

    @Test
    fun `Set-Cookie는 headers가 아니라 cookies 배열로 하나씩 보낸다`() {
        val response = MockHttpServletResponse()
        response.addHeader(HttpHeaders.SET_COOKIE, "__Host-refreshToken=a; Path=/; Expires=Thu, 1 Jan 1970 00:00:00 GMT")
        response.addHeader(HttpHeaders.SET_COOKIE, "other=b; Path=/")

        val payload = FunctionUrlResponse.from(response)

        assertEquals(
            listOf("__Host-refreshToken=a; Path=/; Expires=Thu, 1 Jan 1970 00:00:00 GMT", "other=b; Path=/"),
            payload["cookies"],
        )
        @Suppress("UNCHECKED_CAST")
        val headers = payload["headers"] as Map<String, String>
        assertFalse(headers.keys.any { it.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) })
    }

    @Test
    fun `값이 여럿인 헤더는 콤마로 합치고 하나인 헤더는 그대로 둔다`() {
        val response = MockHttpServletResponse()
        response.addHeader(HttpHeaders.VARY, "Origin")
        response.addHeader(HttpHeaders.VARY, "Access-Control-Request-Method")
        response.addHeader(HttpHeaders.VARY, "Access-Control-Request-Headers")
        response.addHeader(HttpHeaders.CACHE_CONTROL, "no-store")

        @Suppress("UNCHECKED_CAST")
        val headers = FunctionUrlResponse.from(response)["headers"] as Map<String, String>

        assertEquals("Origin, Access-Control-Request-Method, Access-Control-Request-Headers", headers[HttpHeaders.VARY])
        assertEquals("no-store", headers[HttpHeaders.CACHE_CONTROL])
    }

    @Test
    fun `쿠키가 없으면 cookies 키를 싣지 않고 상태와 본문은 그대로 옮긴다`() {
        val response = MockHttpServletResponse()
        response.status = 201
        response.contentType = "application/json"
        response.writer.write("""{"status":201}""")

        val payload = FunctionUrlResponse.from(response)

        assertFalse(payload.containsKey("cookies"))
        assertEquals(201, payload["statusCode"])
        assertEquals("""{"status":201}""", payload["body"])
    }
}
