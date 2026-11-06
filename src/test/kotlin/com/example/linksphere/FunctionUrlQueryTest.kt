package com.example.linksphere

import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockServletContext
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import java.net.URI
import kotlin.test.assertEquals

/**
 * MockMvc가 쿼리를 서블릿 컨테이너와 같게 읽도록 바꾸는 규칙을 고정한다. FE는 공백을 `+`로 보내는데 MockMvc가 이를 글자로
 * 남겨, 두 단어 검색이 한 단어로 처리됐다(2026-11-06 운영에서 확인).
 */
class FunctionUrlQueryTest {

    private fun parameter(query: String, name: String): String? = MockMvcRequestBuilders
        .request(HttpMethod.GET, URI.create("/post?$query"))
        .buildRequest(MockServletContext())
        .getParameter(name)

    @Test
    fun `변환하지 않으면 MockMvc는 +를 글자로 남긴다`() {
        assertEquals("Spring+Boot", parameter("search=Spring+Boot", "search"))
    }

    @Test
    fun `쿼리의 +는 공백으로 읽힌다`() {
        val query = FunctionUrlQuery.toMockMvcQuery("search=Spring+Boot&size=20")

        assertEquals("Spring Boot", parameter(query, "search"))
        assertEquals("20", parameter(query, "size"))
    }

    @Test
    fun `인코딩한 한글 사이의 +도 공백으로 읽힌다`() {
        val query = FunctionUrlQuery.toMockMvcQuery("search=%EB%A6%AC%EC%95%A1%ED%8A%B8+%EC%84%B1%EB%8A%A5")

        assertEquals("리액트 성능", parameter(query, "search"))
    }

    @Test
    fun `글자 그대로의 +는 %2B로 오므로 그대로 남는다`() {
        val query = FunctionUrlQuery.toMockMvcQuery("search=C%2B%2B+tips")

        assertEquals("C++ tips", parameter(query, "search"))
    }

    @Test
    fun `%20과 +가 없는 쿼리는 바뀌지 않는다`() {
        assertEquals("search=Spring%20Boot&size=20", FunctionUrlQuery.toMockMvcQuery("search=Spring%20Boot&size=20"))
    }
}
