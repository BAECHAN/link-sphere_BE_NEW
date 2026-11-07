package com.example.linksphere

import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * URI 문법에 맞지 않는 입력이 함수 실패가 아니라 400으로 끝나도록 판별 규칙을 고정한다.
 */
class FunctionUrlRequestUriTest {

    @Test
    fun `변환하지 않으면 깨진 입력은 예외가 난다`() {
        assertFailsWith<IllegalArgumentException> { URI.create("/post?search=%ZZ") }
    }

    @Test
    fun `쿼리의 깨진 문자는 null이다`() {
        assertNull(FunctionUrlRequestUri.parseOrNull("/post", "size=20&search=%ZZ"))
        assertNull(FunctionUrlRequestUri.parseOrNull("/post", "size=20&search=a|b"))
    }

    @Test
    fun `경로의 깨진 문자는 null이다`() {
        assertNull(FunctionUrlRequestUri.parseOrNull("/post/%ZZ", null))
        assertNull(FunctionUrlRequestUri.parseOrNull("/post/a|b", null))
    }

    @Test
    fun `정상 경로와 쿼리는 그대로 URI가 된다`() {
        val uri = FunctionUrlRequestUri.parseOrNull("/post", "search=%EB%A6%AC%EC%95%A1%ED%8A%B8%20tips&size=20")

        assertEquals("/post", uri?.rawPath)
        assertEquals("search=%EB%A6%AC%EC%95%A1%ED%8A%B8%20tips&size=20", uri?.rawQuery)
    }

    @Test
    fun `쿼리가 없으면 경로만 쓴다`() {
        val uri = FunctionUrlRequestUri.parseOrNull("/post", null)

        assertEquals("/post", uri?.rawPath)
        assertNull(uri?.rawQuery)
    }
}
