package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PostSearchQueryTest {

    @Test
    fun `tokenize splits on whitespace`() {
        assertEquals(listOf("콜드", "스타트"), PostSearchQuery.tokenize("콜드 스타트"))
    }

    @Test
    fun `tokenize collapses multiple whitespaces and trims`() {
        assertEquals(listOf("콜드", "스타트"), PostSearchQuery.tokenize("  콜드   스타트  "))
    }

    @Test
    fun `tokenize lowercases tokens`() {
        assertEquals(listOf("cold", "start"), PostSearchQuery.tokenize("Cold START"))
    }

    @Test
    fun `tokenize keeps a single compound token`() {
        assertEquals(listOf("콜드스타트"), PostSearchQuery.tokenize("콜드스타트"))
    }

    @Test
    fun `tokenize returns empty list for null`() {
        assertEquals(emptyList<String>(), PostSearchQuery.tokenize(null))
    }

    @Test
    fun `tokenize returns empty list for blank`() {
        assertEquals(emptyList<String>(), PostSearchQuery.tokenize("   "))
    }

    // STRIP_CHARS/STRIP_CHARS_KEEP_COMMA는 Postgres regexp_replace용 패턴이지만, 대괄호
    // 문자 클래스 문법은 Java Regex와 호환되므로 여기서도 같은 리터럴로 검증할 수 있다.
    private fun strip(input: String) = input.replace(Regex(PostSearchQuery.STRIP_CHARS), "")

    private fun stripKeepComma(input: String) = input.replace(Regex(PostSearchQuery.STRIP_CHARS_KEEP_COMMA), "")

    @Test
    fun `STRIP_CHARS removes symbols but keeps Korean and English letters`() {
        assertEquals("nng닐슨노먼그룹은뭘하는곳일까", strip("nn/g-닐슨노먼그룹은뭘하는곳일까?"))
    }

    @Test
    fun `STRIP_CHARS removes a comma`() {
        assertEquals("ab", strip("a,b"))
    }

    @Test
    fun `STRIP_CHARS_KEEP_COMMA keeps commas between tags but removes other symbols`() {
        assertEquals("라이프스타일,데이터", stripKeepComma("라이프스타일,데이터!"))
    }
}
