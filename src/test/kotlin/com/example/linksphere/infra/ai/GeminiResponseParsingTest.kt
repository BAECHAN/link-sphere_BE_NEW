package com.example.linksphere.infra.ai

import com.example.linksphere.infra.ai.dto.Candidate
import com.example.linksphere.infra.ai.dto.Content
import com.example.linksphere.infra.ai.dto.GeminiResponse
import com.example.linksphere.infra.ai.dto.Part
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GeminiResponseParsingTest {

    private val geminiService = GeminiService(apiKey = "test-key", models = listOf("test-model"))

    private fun geminiResponse(text: String) = GeminiResponse(
        candidates = listOf(Candidate(content = Content(parts = listOf(Part(text = text))), finishReason = "STOP", index = 0)),
    )

    @Test
    fun `parseResponse extracts all four sections`() {
        val text =
            """
            TITLE: 리액트 19 신기능 정리
            DESCRIPTION: 리액트 19의 주요 변경사항을 정리한 글입니다.
            SUMMARY: 리액트 19의 새로운 기능을 소개합니다.
            TAGS: React, 프론트엔드, JavaScript
            """.trimIndent()

        val result = geminiService.parseResponse(geminiResponse(text))

        assertEquals("리액트 19 신기능 정리", result.title)
        assertEquals("리액트 19의 주요 변경사항을 정리한 글입니다.", result.description)
        assertEquals("리액트 19의 새로운 기능을 소개합니다.", result.summary)
        assertEquals(listOf("React", "프론트엔드", "JavaScript"), result.tags)
    }

    @Test
    fun `parseResponse treats blank TITLE and DESCRIPTION as absent`() {
        val text =
            """
            TITLE:
            DESCRIPTION:
            SUMMARY: 요약 내용입니다.
            TAGS: 태그1, 태그2
            """.trimIndent()

        val result = geminiService.parseResponse(geminiResponse(text))

        assertNull(result.title)
        assertNull(result.description)
        assertEquals("요약 내용입니다.", result.summary)
        assertEquals(listOf("태그1", "태그2"), result.tags)
    }

    @Test
    fun `parseResponse falls back to full text as summary when no labels are present`() {
        val text = "이것은 라벨이 전혀 없는 평문 응답입니다."

        val result = geminiService.parseResponse(geminiResponse(text))

        assertEquals(text, result.summary)
        assertNull(result.title)
        assertNull(result.description)
        assertEquals(emptyList<String>(), result.tags)
    }

    @Test
    fun `parseResponse separates TAGS cleanly when the label is wrapped in markdown emphasis`() {
        val text = "SUMMARY: 이 글은 테스트용 요약입니다.\n**TAGS:** React, Vue, 테스트"

        val result = geminiService.parseResponse(geminiResponse(text))

        assertEquals("이 글은 테스트용 요약입니다.", result.summary)
        assertEquals(listOf("React", "Vue", "테스트"), result.tags)
    }

    @Test
    fun `parseResponse discards a TITLE that exceeds the length safety net`() {
        val longTitle = "가".repeat(130)
        val text = "TITLE: $longTitle\nSUMMARY: 요약입니다.\nTAGS: 태그1"

        val result = geminiService.parseResponse(geminiResponse(text))

        assertNull(result.title)
        assertEquals("요약입니다.", result.summary)
    }

    @Test
    fun `parseResponse keeps TAGS when SUMMARY is left blank`() {
        // 2026-09-06 04:30 프로덕션 실제 응답 원문 - 본문이 사실상 없는 YouTube 링크에
        // 대해 모델이 SUMMARY만 비우고 TAGS는 채워서 돌려준 케이스.
        val text =
            """
            TITLE:
            DESCRIPTION:
            SUMMARY:
            TAGS: AI, 학습, 미래 교육, 자기계발
            """.trimIndent()

        val result = geminiService.parseResponse(geminiResponse(text))

        assertNull(result.summary)
        assertEquals(listOf("AI", "학습", "미래 교육", "자기계발"), result.tags)
    }
}
