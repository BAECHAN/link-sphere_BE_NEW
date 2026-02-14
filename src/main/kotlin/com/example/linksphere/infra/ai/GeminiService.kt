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
        @Value("\${gemini.api.model:gemini-1.5-flash}") private val model: String
) {
    private val logger = LoggerFactory.getLogger(GeminiService::class.java)
    private val restClient = RestClient.create()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

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
            SUMMARY: [핵심 내용 3문장 요약]
            TAGS: [관련 키워드 3~5개를 쉼표로 구분]

            태그 규칙:
            - 각 태그는 반드시 1~2단어의 짧은 키워드여야 함 (예: "React", "웹개발", "머신러닝")
            - 문장이나 긴 구절은 절대 태그로 사용하지 말 것
            - 한글 또는 영어 단어 사용
            - '#' 기호 붙이지 말 것
            - 예시: "JavaScript, 프론트엔드, API, 성능최적화, TypeScript"
        """.trimIndent()

        val request = GeminiRequest(contents = listOf(Content(parts = listOf(Part(text = prompt)))))

        return try {
            val response =
                    restClient
                            .post()
                            .uri("$baseUrl/$model:generateContent?key=$apiKey")
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(request)
                            .retrieve()
                            .body(GeminiResponse::class.java)

            parseResponse(response)
        } catch (e: Exception) {
            logger.error("Failed to call Gemini API", e)
            AiAnalysisResult(null, emptyList())
        }
    }

    private fun parseResponse(response: GeminiResponse?): AiAnalysisResult {
        val text =
                response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                        ?: return AiAnalysisResult(null, emptyList())

        var summary: String? = null
        val tags = mutableListOf<String>()

        val summaryMatch = Regex("SUMMARY:\\s*([\\s\\S]+?)(?=TAGS:|$)").find(text)
        val tagsMatch = Regex("TAGS:\\s*([\\s\\S]+?)$").find(text)

        if (summaryMatch != null) {
            summary = summaryMatch.groupValues[1].trim()
        }

        if (tagsMatch != null) {
            val tagsText = tagsMatch.groupValues[1].trim()
            tags.addAll(
                    tagsText.split(",").map { it.trim().removePrefix("#").trim() }.filter {
                        it.isNotBlank() && it.length <= 20
                    }
            )
        }

        return AiAnalysisResult(summary, tags)
    }
}
