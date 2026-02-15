package com.example.linksphere.infra.ai

import com.example.linksphere.infra.ai.dto.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

@Service
class GeminiService(
        @Value("\${gemini.api.key}") private val apiKey: String,
        @Value("\${gemini.api.model:gemini-2.5-flash}") private val model: String
) {
    private val logger = LoggerFactory.getLogger(GeminiService::class.java)
    private val restClient = RestClient.create()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    init {
        logger.info("[GeminiService] Initialized with model: $model")
    }

    fun analyzeContent(title: String, description: String?, content: String): AiAnalysisResult {
        if (apiKey.isBlank() || apiKey == "your-api-key-here") {
            logger.warn("Gemini API Key is missing or invalid. Skipping analysis.")
            return AiAnalysisResult(null, emptyList())
        }

        val prompt =
                """
            다음 웹페이지를 분석해서 요약과 태그를 추출해줘:

            제목: $title
            설명: ${description ?: ""}
            내용: $content

            반드시 다음 형식을 지켜서 응답해줘:
            SUMMARY: [핵심 내용 10문장 요약]
            TAGS: [관련 키워드 3~5개를 쉼표로 구분]

            태그 규칙:
            - 각 태그는 반드시 1~2단어의 짧은 키워드여야 함 (예: "React", "웹개발", "머신러닝")
            - 문장이나 긴 구절은 절대 태그로 사용하지 말 것
            - 한글 또는 영어 단어 사용
            - '#' 기호 붙이지 말 것
            - 예시: "JavaScript, 프론트엔드, API, 성능최적화, TypeScript"
        """.trimIndent()

        val request = GeminiRequest(contents = listOf(Content(parts = listOf(Part(text = prompt)))))

        logger.info("[Gemini API] Requesting analysis for title: $title")
        val response =
                restClient
                        .post()
                        .uri("$baseUrl/$model:generateContent?key=$apiKey")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(request)
                        .retrieve()
                        .body(GeminiResponse::class.java)

        logger.info("[Gemini API] Response received")
        return parseResponse(response)
    }

    private fun parseResponse(response: GeminiResponse?): AiAnalysisResult {
        val text = response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

        if (text == null) {
            logger.warn("[Gemini API] Response text is null. Response: $response")
            return AiAnalysisResult(null, emptyList())
        }

        logger.info("[Gemini API] Raw Response Text: $text")

        var summary: String? = null
        val tags = mutableListOf<String>()

        val summaryMatch =
                Regex(
                                "SUMMARY:\\s*([\\s\\S]+?)(?=TAGS:|\\*\\*TAGS:|\\s*TAGS:|$)",
                                RegexOption.IGNORE_CASE
                        )
                        .find(text)
        val tagsMatch = Regex("TAGS:\\s*([\\s\\S]+?)$", RegexOption.IGNORE_CASE).find(text)

        if (summaryMatch != null) {
            summary = summaryMatch.groupValues[1].trim()
        } else if (!text.contains("TAGS:", ignoreCase = true)) {
            // "SUMMARY:" 또는 "TAGS:"가 없으면 전체 텍스트를 요약으로 간주
            summary = text.trim()
        } else if (text.contains("SUMMARY:", ignoreCase = true)) {
            // Prefix는 있는데 Regex가 실패한 경우 (드문 경우)
            val startIndex = text.indexOf("SUMMARY:", ignoreCase = true) + 8
            val endIndex =
                    text.indexOf("TAGS:", ignoreCase = true).let {
                        if (it == -1) text.length else it
                    }
            summary = text.substring(startIndex, endIndex).trim()
        }

        if (tagsMatch != null) {
            val tagsText = tagsMatch.groupValues[1].trim()
            tags.addAll(
                    tagsText.split(",").map { it.trim().removePrefix("#").trim() }.filter {
                        it.isNotBlank() && it.length <= 20
                    }
            )
        }

        logger.info("[Gemini API] Parsed Summary: ${summary?.take(50)}..., Tags: $tags")
        return AiAnalysisResult(summary, tags)
    }
}
