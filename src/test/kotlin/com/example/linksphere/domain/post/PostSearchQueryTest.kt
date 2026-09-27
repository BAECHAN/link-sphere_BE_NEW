package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class PostSearchQueryTest {

    private fun post(title: String, description: String? = null, tags: List<String>? = null, aiSummary: String? = null) = TablePost(
        userId = UUID.randomUUID(),
        url = "https://example.com",
        title = title,
        description = description,
        tags = tags,
        aiSummary = aiSummary,
    )

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

    @Test
    fun `matchesKeywordLiterally는 제목에 토큰이 있으면 true`() {
        assertTrue(PostSearchQuery.matchesKeywordLiterally(post(title = "Zustand 입문 가이드"), listOf("zustand")))
    }

    @Test
    fun `matchesKeywordLiterally는 토큰이 어디에도 없으면 false`() {
        val target = post(title = "Zustand 입문 가이드", description = "가벼운 상태 저장소", tags = listOf("React"))
        assertFalse(PostSearchQuery.matchesKeywordLiterally(target, listOf("상태관리")))
    }

    @Test
    fun `matchesKeywordLiterally는 기호가 섞인 제목도 스트립된 형태로 찾는다`() {
        assertTrue(PostSearchQuery.matchesKeywordLiterally(post(title = "NN/g - 닐슨 노먼 그룹"), listOf("nng")))
    }

    @Test
    fun `matchesKeywordLiterally는 태그 목록에서도 찾는다`() {
        assertTrue(PostSearchQuery.matchesKeywordLiterally(post(title = "제목", tags = listOf("Zustand", "React")), listOf("zustand")))
    }

    @Test
    fun `matchesKeywordLiterally는 토큰이 없으면 false`() {
        assertFalse(PostSearchQuery.matchesKeywordLiterally(post(title = "아무 글"), emptyList()))
    }
}
