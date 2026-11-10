package com.example.linksphere

import com.amazonaws.serverless.proxy.internal.servlet.AwsHttpApiV2ProxyHttpServletRequest
import com.amazonaws.serverless.proxy.model.ContainerConfig
import com.amazonaws.serverless.proxy.model.HttpApiV2HttpContext
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequestContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * serverless-java-container가 쿼리를 Tomcat과 같게 읽도록 고치는 규칙을 고정한다. 라이브러리는 쌍을 `split("=")`로 나눠
 * 값 안의 `=`, 이름만 있는 쌍, 이름이 빈 쌍을 Tomcat과 다르게 읽는다(2026-11-10 계획에서 확인). 파라미터는 운영과 같은
 * 라이브러리 요청 객체로 읽는다.
 */
class FunctionUrlQueryTest {

    private fun request(query: String): AwsHttpApiV2ProxyHttpServletRequest {
        val http = HttpApiV2HttpContext()
        http.method = "GET"
        http.path = "/api/post"
        val requestContext = HttpApiV2ProxyRequestContext()
        requestContext.http = http
        val event = HttpApiV2ProxyRequest()
        event.rawPath = "/api/post"
        event.rawQueryString = query
        event.requestContext = requestContext
        val config = ContainerConfig.defaultConfig()
        config.isQueryStringCaseSensitive = true
        return AwsHttpApiV2ProxyHttpServletRequest(event, null, null, config)
    }

    private fun parameter(query: String, name: String): String? = request(query).getParameter(name)

    @Test
    fun `고치지 않으면 값 안의 =에서 검색어가 사라진다`() {
        assertEquals("", parameter("search=a=b", "search"))
    }

    @Test
    fun `값 안의 =는 첫 =만 나눠 Tomcat처럼 읽힌다`() {
        val query = FunctionUrlQuery.normalize("size=20&search=a=b")

        assertEquals("a=b", parameter(query, "search"))
        assertEquals("20", parameter(query, "size"))
    }

    @Test
    fun `이름만 있는 쌍은 빈 값으로 읽힌다`() {
        assertEquals("", parameter(FunctionUrlQuery.normalize("size=20&search"), "search"))
    }

    @Test
    fun `고치지 않으면 =만 있는 쌍에서 요청을 읽다가 예외가 난다`() {
        assertFailsWith<ArrayIndexOutOfBoundsException> { parameter("size=20&=", "size") }
    }

    @Test
    fun `이름이 빈 쌍과 빈 조각은 버린다`() {
        assertEquals("size=20", FunctionUrlQuery.normalize("size=20&="))
        assertEquals("", FunctionUrlQuery.normalize("="))
        assertEquals("a=1&b=2", FunctionUrlQuery.normalize("a=1&&b=2&=x"))
        assertEquals("20", parameter(FunctionUrlQuery.normalize("size=20&="), "size"))
    }

    @Test
    fun `+는 라이브러리가 공백으로 읽고 %3D와 %2B는 그대로 둔다`() {
        val query = FunctionUrlQuery.normalize("search=Spring+Boot&eq=a%3Db&plus=C%2B%2B")

        assertEquals("search=Spring+Boot&eq=a%3Db&plus=C%2B%2B", query)
        assertEquals("Spring Boot", parameter(query, "search"))
        assertEquals("a=b", parameter(query, "eq"))
        assertEquals("C++", parameter(query, "plus"))
    }

    @Test
    fun `쿼리 키 대소문자를 가린다`() {
        assertEquals(null, parameter(FunctionUrlQuery.normalize("SIZE=1"), "size"))
    }
}
