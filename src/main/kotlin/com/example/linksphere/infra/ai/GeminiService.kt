package com.example.linksphere.infra.ai

import com.example.linksphere.infra.ai.dto.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Service
class GeminiService(
    @Value("\${gemini.api.key}") private val apiKey: String,
    // 앞에서부터 시도하고, 쿼터 소진(429)·서버 오류(5xx)면 다음 모델로 폴백한다
    @Value("\${gemini.api.models:gemini-2.5-flash,gemini-3.1-flash-lite}")
    private val models: List<String>,
) {
    private val logger = LoggerFactory.getLogger(GeminiService::class.java)

    // 타임아웃 미설정 시 무제한 대기 → 별도 Lambda 호출(AiJobDispatcher)에서 AI 분석 전용으로
    // 실행되므로 CloudFront 60초 제약은 없지만, Lambda 함수 자체 타임아웃(120초) 안에는
    // 들어와야 하므로 커넥트/응답 상한은 그대로 둔다.
    private val requestFactory =
        JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
            .apply { setReadTimeout(Duration.ofSeconds(45)) }
    private val restClient = RestClient.builder().requestFactory(requestFactory).build()
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
            다음 웹페이지를 분석해서 제목·설명·요약과 태그를 추출해줘:

            제목: $title
            설명: ${description ?: ""}
            내용: $content

            반드시 다음 형식을 지켜서 응답해줘:
            TITLE: [내용을 대표하는 제목 60자 이내]
            DESCRIPTION: [내용을 1~2문장으로 요약한 짧은 설명]
            SUMMARY: [핵심 내용 10문장 요약]
            TAGS: [관련 키워드 3~5개를 쉼표로 구분]

            제목·설명 규칙:
            - 위 '제목'이 이미 채워져 있으면 TITLE: 뒤를 비워둘 것
            - 위 '설명'이 이미 채워져 있으면 DESCRIPTION: 뒤를 비워둘 것
            - 내용이 부족해 판단할 수 없으면 해당 항목 뒤를 비워둘 것
            - 원문 언어를 그대로 쓸 것 (한국어 페이지면 한국어로)
            - 제목에 URL·사이트명·"홈" 같은 무의미한 값을 넣지 말 것

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

    // analyzeContent와 classifyCategories(PostCategoryClassifier 경유)를 순차가 아닌 병렬로
    // 돌리기 위한 래퍼. 반드시 다른 빈(PostAIService)에서 호출해야 @Async 프록시가 적용된다 —
    // 같은 빈 내부에서 this로 호출하면 프록시를 우회해 동기로 실행된다.
    @Async
    fun analyzeContentAsync(title: String, description: String?, content: String): CompletableFuture<AiAnalysisResult> = CompletableFuture.completedFuture(analyzeContent(title, description, content))

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

    // parseResponse가 섹션 경계를 인식하는 데 쓰는 라벨. 프롬프트의 출력 형식과 반드시 일치해야 한다.
    private val sectionLabels = listOf("TITLE", "DESCRIPTION", "SUMMARY", "TAGS")

    // "LABEL:" 다음부터 다른 라벨이 나오기 전까지를 잘라낸다.
    // 모델이 **LABEL:** 처럼 마크다운 강조를 붙여 오는 경우가 있어 앞의 * 는 허용하고,
    // "LABEL:**"처럼 콜론 바로 뒤에 닫는 강조 기호가 남는 경우도 값에서 걷어낸다.
    private fun extractSection(text: String, label: String): String? {
        val others = sectionLabels.filter { it != label }.joinToString("|")
        return Regex("$label:\\s*([\\s\\S]*?)(?=\\**\\s*(?:$others)\\s*:|$)", RegexOption.IGNORE_CASE)
            .find(text)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.trimStart('*')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    internal fun parseResponse(response: GeminiResponse?): AiAnalysisResult {
        val text = response?.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

        if (text == null) {
            logger.warn("[Gemini API] Response text is null. Response: $response")
            return AiAnalysisResult(null, emptyList())
        }

        logger.info("[Gemini API] Raw Response Text: $text")

        // 모델이 형식을 완전히 무시한 경우에만 전체 텍스트를 요약으로 간주한다.
        // 라벨이 하나라도 있으면 그건 형식을 지킨 응답이므로 TITLE/DESCRIPTION까지 요약에 섞으면 안 된다.
        val summary =
            extractSection(text, "SUMMARY")
                ?: text.trim().takeIf { sectionLabels.none { l -> text.contains("$l:", ignoreCase = true) } }

        val tags =
            extractSection(text, "TAGS")
                ?.split(",")
                ?.map { it.trim().removePrefix("#").trim() }
                ?.filter { it.isNotBlank() && it.length <= 20 }
                ?: emptyList()

        // 모델이 요약 전체를 TITLE/DESCRIPTION에 쏟아붓는 오작동을 막는 안전망 — 길이 초과는 잘라내지 않고 버린다.
        val title = extractSection(text, "TITLE")?.takeIf { it.length <= 120 }
        val description = extractSection(text, "DESCRIPTION")?.takeIf { it.length <= 500 }

        logger.info("[Gemini API] Parsed Summary: ${summary?.take(50)}..., Tags: $tags")
        return AiAnalysisResult(summary, tags, title, description)
    }
}
