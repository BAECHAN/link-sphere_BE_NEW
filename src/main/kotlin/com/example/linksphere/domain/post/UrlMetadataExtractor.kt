package com.example.linksphere.domain.post

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

private const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MAX_REDIRECTS = 5

// FeedParser.MAX_CONTENT_LENGTH와 같은 값으로 맞춘다 - 둘이 서로의 폴백이라 성격을 같게 둔다.
private const val MAX_CONTENT_LENGTH = 5000

// 긁어온 본문 텍스트의 하한. 이 길이에 못 미치면 "본문을 못 건졌다"로 보고 null을 돌려
// PostService.createPost / PostAiBackfillRunner의 폴백 엘비스가 발동하게 한다.
// 실측 근거(2026-09, 프로덕션 URL 직접 크롤링): 페이지 껍데기만 긁힌 사례가
// d2.naver.com 150자(네비게이션) / YouTube 358자(푸터) / tech.kakao.com 742자(네비+제목) /
// smartstore.naver.com 817자(봇 차단 안내문)였다. 가장 긴 껍데기(817)보다 위, 실제 기사
// 본문(수천 자)보다 한참 아래인 값으로 1,000을 잡는다.
private const val MIN_PAGE_CONTENT_LENGTH = 1000

// og:description·JSON-LD description처럼 "사람이 직접 쓴" 값에 적용하는 하한.
// 본문 하한보다 훨씬 낮다 - 네비·푸터가 섞일 구조적 여지가 없어 짧아도 신호가 진짜이기 때문이다.
private const val MIN_META_DESCRIPTION_LENGTH = 40

data class UrlMetadata(
    val title: String,
    val description: String?,
    val ogImage: String?,
    val tags: List<String>,
    val pageContent: String?,
)

@Component
class UrlMetadataExtractor(
    private val objectMapper: ObjectMapper,
    private val safeUrlValidator: SafeUrlValidator,
) {

    private val logger = LoggerFactory.getLogger(UrlMetadataExtractor::class.java)

    fun extract(url: String): UrlMetadata = try {
        val response = safeConnect(url)
        var metadata = parseMetadata(response.parse(), url, response.statusCode())

        if (isYoutubeUrl(url)) {
            val youtubeMeta = fetchYoutubeMetadata(url)
            if (youtubeMeta != null) {
                var title = metadata.title
                var ogImage = metadata.ogImage
                if (!youtubeMeta["title"].isNullOrBlank()) title = youtubeMeta["title"]!!
                if (ogImage == null && !youtubeMeta["thumbnail_url"].isNullOrBlank()) {
                    ogImage = youtubeMeta["thumbnail_url"]
                }
                metadata = metadata.copy(title = title, ogImage = ogImage)
            }
        }

        // 크롤링 대상 사이트가 og:image를 http로 내리는 경우가 있다 - FE가 https로
        // 서빙되는 이상 그대로 저장하면 Mixed Content 경고가 뜨므로 저장 전에 정규화한다.
        metadata.copy(ogImage = metadata.ogImage?.replace(Regex("^http://"), "https://"))
    } catch (e: Exception) {
        logger.error("[Crawling] 크롤링 실패: $url", e)
        UrlMetadata(title = url.take(100), description = null, ogImage = null, tags = emptyList(), pageContent = null)
    }

    /**
     * 네트워크 없이 검증할 수 있도록 분리한 순수 함수 - FeedParser.parse(xml)와 같은 이유.
     * [statusCode]를 받는 이유는 safeConnect가 ignoreHttpErrors로 4xx 응답도 그대로 넘겨주기
     * 때문이다 - 에러 페이지에서 무엇을 인정할지는 상태코드를 봐야 정할 수 있다.
     */
    fun parseMetadata(doc: Document, url: String, statusCode: Int = 200): UrlMetadata {
        val ok = statusCode in 200..299

        val title = doc.select("meta[property=og:title]")
            .attr("content")
            // 403 에러 페이지의 <title>(Cloudflare는 "Just a moment..." - 실측)까지 제목으로
            // 승격하면 WeakTitleDetector가 "쓸 만한 제목"으로 오인해 AI 제목 대체를 막아버려
            // 지금보다 나빠진다. 2xx가 아니면 사이트가 명시적으로 심은 og:title만 인정한다.
            .ifEmpty { if (ok) doc.title() else "" }
            // 폴백은 반드시 절삭한다 - 긴 URL 원문이 제목이 되는 것을 막는 계약이다
            // (FE docs/DECISIONS.md "크롤링 실패 폴백만 100자 절삭"). ignoreHttpErrors 도입으로
            // 403이 더 이상 catch 블록을 타지 않게 되면서 이 절삭이 여기로 옮겨왔다.
            .ifEmpty { url.take(100) }
        val description = doc.select("meta[property=og:description]").attr("content").ifEmpty { null }
        // abs:는 og:image가 상대경로("/img/thumb.png")인 사이트를 baseUri(safeConnect가 리다이렉트를
        // 다 따라간 최종 URL) 기준으로 절대 URL화한다. 절대화에 실패하면(속성 자체가 없는 등) 빈
        // 문자열이라 원래 값으로 폴백한다.
        val ogImage = doc.select("meta[property=og:image]")
            .let { it.attr("abs:content").ifEmpty { it.attr("content") } }
            .ifEmpty { null }

        val tags = mutableListOf<String>()
        val host = URI(url).host.replace("www.", "")
        if (host.isNotEmpty()) tags.add(host)

        return UrlMetadata(
            title = title,
            description = description,
            ogImage = ogImage,
            tags = tags,
            // 에러 페이지 본문은 무슨 내용이든 이 페이지의 내용이 아니다.
            pageContent = if (ok) resolvePageContent(doc, description, url) else null,
        )
    }

    /**
     * 본문 소스를 우선순위대로 시도하고, 어느 것도 하한을 넘지 못하면 null을 돌려준다.
     * null이어야 PostService.kt의 `metadata.pageContent ?: fallbackContent` 엘비스와
     * PostAiBackfillRunner의 RSS 폴백이 발동한다 - 빈 문자열("")을 돌려주면 non-null이라
     * 그 폴백이 영영 안 걸린다(2026-09 이전의 결함).
     */
    private fun resolvePageContent(doc: Document, ogDescription: String?, url: String): String? {
        // (1) 일반 페이지 본문.
        normalizeContent(doc.body().text())
            ?.takeIf { it.length >= MIN_PAGE_CONTENT_LENGTH }
            ?.let { return it }

        // (2) 본문이 껍데기(네비·푸터·봇 차단 안내)뿐일 때의 폴백. JSON-LD articleBody는 기사
        //     전문을 담는 사이트가 있어 진짜 정보 이득이고, description류는 이미 Gemini 프롬프트의
        //     '설명' 필드로 따로 들어가므로(GeminiService.analyzeContent) 정보 이득은 없다 -
        //     그럼에도 넣는 이유는 PostService의 `aiStatus = if (pageContent != null) PENDING`
        //     게이트를 통과시켜 태그·카테고리·AI 제목만이라도 건지기 위해서다.
        val jsonLd = jsonLdNodes(doc)
        val fallback = listOfNotNull(
            jsonLd.firstNotNullOfOrNull { it.path("articleBody").textValue() },
            jsonLd.firstNotNullOfOrNull { it.path("description").textValue() },
            ogDescription,
        ).firstNotNullOfOrNull { candidate ->
            normalizeContent(candidate)?.takeIf { it.length >= MIN_META_DESCRIPTION_LENGTH }
        }

        // 본문을 못 건진 것은 지금까지 아무 흔적도 남기지 않아(빈 문자열이 정상 본문으로 흘렀다)
        // 가짜 요약이 몇 달간 발견되지 않았다. 폴백 경로로 넘어간 사실은 로그로 남긴다.
        if (fallback == null) {
            logger.info("[Crawling] 본문 하한 미달 - $url, bodyTextLength=${doc.body().text().trim().length}")
        }
        return fallback
    }

    // 정규화·상한 방식은 FeedParser.toPlainText와 동일하게 맞춘다 - 서로의 폴백이라 성격을 같게 둔다.
    private fun normalizeContent(raw: String): String? = raw.replace("\\s+".toRegex(), " ").trim()
        .take(MAX_CONTENT_LENGTH).ifEmpty { null }

    /**
     * <script type="application/ld+json"> 블록들. 사이트에 따라 최상위가 배열이거나 @graph로
     * 감싸므로 한 겹 펼쳐서 객체 목록으로 돌려준다. 유효하지 않은 JSON을 심는 사이트가 있어
     * 블록 단위로 실패를 흡수한다.
     */
    private fun jsonLdNodes(doc: Document): List<JsonNode> = doc.select("script[type=application/ld+json]")
        .mapNotNull { runCatching { objectMapper.readTree(it.data()) }.getOrNull() }
        .flatMap { node -> if (node.isArray) node.toList() else listOf(node) }
        .flatMap { node -> node.get("@graph")?.toList() ?: listOf(node) }

    /**
     * Jsoup의 자동 리다이렉트를 끄고 직접 따라가면서, 매 홉마다 SafeUrlValidator로 재검증한다.
     * 공개 URL이 응답에서 사설 IP로 리다이렉트하는 SSRF 우회를 막기 위함이다.
     *
     * FeedParser가 RSS/Atom 피드를 가져올 때도 이 검증된 로직을 그대로 재사용한다.
     */
    fun safeConnect(url: String): org.jsoup.Connection.Response {
        var currentUrl = url
        var hop = 0
        while (true) {
            safeUrlValidator.validate(currentUrl)
            val response =
                Jsoup.connect(currentUrl)
                    .userAgent(USER_AGENT)
                    .referrer("http://google.com")
                    .timeout(5000)
                    // YouTube watch 페이지 HTML이 실측 1.3~1.4MB고, Jsoup 기본 상한 2MB는 여유가
                    // 1.5배뿐인 데다, 넘어도 예외 없이 조용히 잘린다. 이 상한은 gzip 해제 후
                    // 바이트에 적용된다.
                    .maxBodySize(4 * 1024 * 1024)
                    .followRedirects(false)
                    // 리다이렉트 응답(예: youtu.be → youtube.com)은 Content-Type이
                    // text/html이 아닌 경우가 많아(예: application/binary), 검사를 끄지
                    // 않으면 아래 상태코드 분기 전에 execute()가 예외를 던져버린다.
                    .ignoreContentType(true)
                    // 403(Cloudflare·AWS IP 차단)이면 지금은 execute()가 던져 og:title조차 못
                    // 건지고 제목이 URL 문자열로 폴백된다. 응답을 그대로 받아 최소한 og:*는 읽는다.
                    // 이 옵션이 끄는 건 "상태코드 <200 || >=400 일 때의 throw"뿐이라 아래 300~399
                    // 리다이렉트 분기와는 간섭하지 않는다 - 3xx는 원래부터 던지지 않았다.
                    .ignoreHttpErrors(true)
                    .execute()

            if (response.statusCode() !in 300..399) return response

            hop++
            if (hop > MAX_REDIRECTS) throw IllegalStateException("Too many redirects: $url")
            val location = response.header("Location") ?: throw IllegalStateException("Redirect without Location: $currentUrl")
            currentUrl = URI(currentUrl).resolve(location).toString()
        }
    }

    private fun isYoutubeUrl(url: String) = url.contains("youtube.com") || url.contains("youtu.be")

    private fun fetchYoutubeMetadata(url: String): Map<String, String>? = try {
        val oembedUrl = "https://www.youtube.com/oembed?url=$url&format=json"
        val json = Jsoup.connect(oembedUrl).ignoreContentType(true).execute().body()
        val node = objectMapper.readTree(json)
        mapOf(
            "title" to (node.get("title")?.asText() ?: ""),
            "thumbnail_url" to (node.get("thumbnail_url")?.asText() ?: ""),
        )
    } catch (e: Exception) {
        logger.warn("Failed to fetch YouTube oEmbed data for: $url", e)
        null
    }
}
