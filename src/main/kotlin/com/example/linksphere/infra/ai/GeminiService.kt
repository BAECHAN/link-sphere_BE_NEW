package com.example.linksphere.infra.ai

import com.example.linksphere.infra.ai.dto.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient

@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val apiKey: String,
    // 앞에서부터 시도하고, 쿼터 소진(429)·서버 오류(5xx)면 다음 모델로 폴백한다
    @Value("\${gemini.api.models:gemini-2.5-flash,gemini-3.1-flash-lite}")
    private val models: List<String>,
) {
    private val logger = LoggerFactory.getLogger(GeminiService::class.java)
    private val restClient = RestClient.create()
    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models"

    init {
        logger.info("[GeminiService] Initialized with models: $models")
    }

    /**
     * 모델 목록을 순서대로 시도한다.
     * 다음 경우에만 다음 모델로 넘어간다.
     *  - 429: 쿼터·레이트 초과 (RPM 초과는 1분을 기다려야 회복되므로 대기 없이 바로 폴백)
     *  - 5xx: 일시적 과부하(503 등)
     *  - 404: 모델이 없거나 지원 종료됨 (모델 은퇴 시 서비스가 멈추지 않도록)
     * 인증(401/403)·요청 형식(400) 오류는 모델을 바꿔도 동일하게 실패하므로 즉시 던진다.
     */
    private fun generateContent(prompt: String): GeminiResponse? {
        val request = GeminiRequest(contents = listOf(Content(parts = listOf(Part(text = prompt)))))

        models.forEachIndexed { index, model ->
            try {
                return restClient
                    .post()
                    .uri("$baseUrl/$model:generateContent?key=$apiKey")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(GeminiResponse::class.java)
            } catch (e: HttpStatusCodeException) {
                val status = e.statusCode
                val isRetryable = status.value() == 429 || status.value() == 404 || status.is5xxServerError
                if (!isRetryable || index == models.lastIndex) throw e
                logger.warn("[Gemini API] $model 실패($status) → 다음 모델로 폴백 (모델명 오타 여부 확인 필요)")
            }
        }

        return null
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

        logger.info("[Gemini API] Requesting analysis for title: $title")
        val response = generateContent(prompt)

        logger.info("[Gemini API] Response received")
        return parseResponse(response)
    }

    fun classifyCategories(
        title: String,
        description: String?,
        tags: List<String>,
        availableCategories: List<String>,
    ): List<String> {
        if (apiKey.isBlank() || apiKey == "your-api-key-here") {
            logger.warn("Gemini API Key is missing or invalid. Skipping classification.")
            return emptyList()
        }
        if (availableCategories.isEmpty()) return emptyList()

        val prompt =
            """
            다음 게시글을 아래 '카테고리 목록' 중에서 분류해줘:

            제목: $title
            설명: ${description ?: ""}
            태그: ${tags.joinToString(", ")}

            카테고리 목록: ${availableCategories.joinToString(", ")}

            반드시 다음 형식을 지켜서 응답해줘:
            CATEGORY: [위 목록에 있는 카테고리 이름만 쉼표로 구분해서 1~2개]

            규칙:
            - 반드시 위 '카테고리 목록'에 있는 이름만 그대로 사용할 것 (새 카테고리 만들지 말 것)
            - 적합한 카테고리가 없으면 CATEGORY: 뒤를 비워둘 것
            """.trimIndent()

        logger.info("[Gemini API] Requesting category classification for title: $title")
        val response = generateContent(prompt)

        return parseCategories(response)
    }

    private fun parseCategories(response: GeminiResponse?): List<String> {
        val text = response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            ?: return emptyList()

        val match =
            Regex("CATEGORY:\\s*([\\s\\S]+?)$", RegexOption.IGNORE_CASE).find(text)
                ?: return emptyList()

        return match.groupValues[1]
            .trim()
            .split(",")
            .map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotBlank() }
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
                RegexOption.IGNORE_CASE,
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
                },
            )
        }

        logger.info("[Gemini API] Parsed Summary: ${summary?.take(50)}..., Tags: $tags")
        return AiAnalysisResult(summary, tags)
    }
}
