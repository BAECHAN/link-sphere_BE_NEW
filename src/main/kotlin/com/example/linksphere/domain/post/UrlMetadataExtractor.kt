package com.example.linksphere.domain.post

import com.example.linksphere.infra.youtube.YoutubeVideoClient
import com.example.linksphere.infra.youtube.dto.YoutubeSnippet
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

private const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// Lambda가 도쿄(ap-northeast-1) IP로 나가므로, 이 헤더가 없으면 IP 기반 지역 판별을 쓰는
// 사이트(예: 인프런)가 한국 URL을 등록해도 일본어판을 내려준다(2026-09-27 실측,
// docs/AI-ASYNC-PROCESSING.md §5.9). q값 있는 en 폴백을 남겨 한국어판이 없는 사이트는
// 지금처럼 영어를 받게 한다.
private const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
private const val MAX_REDIRECTS = 5

// 인프런처럼 CloudFront 엣지가 접속 국가로 언어를 정하되 그 사실을 리다이렉트로 드러내지
// 않는 사이트가 있다 - Accept-Language 헤더도 무시한다(2026-09-27 도쿄 Lambda 재현,
// docs/AI-ASYNC-PROCESSING.md §5.9). 반면 언어 접두 경로(예: /ko/course/...)는 접속 지역과
// 무관하게 그 언어를 그대로 준다는 것까지 같은 재현으로 확인했다. 호스트 → 강제 로케일
// 맵으로 둬 같은 패턴의 사이트가 늘면 한 줄만 추가하면 된다.
private val LOCALE_OVERRIDE_HOSTS = mapOf("inflearn.com" to "ko")

// 사용자가 명시적으로 다른 언어 링크(예: /en/course/...)를 등록했으면 그 선택을 덮어쓰지
// 않는다 - 인프런이 실제로 쓰는 로케일 4개(hreflang alternate 링크 실측, 2026-09-27).
private val KNOWN_LOCALE_SEGMENTS = setOf("ko", "en", "vi", "ja")

// FeedParser.MAX_CONTENT_LENGTH와 같은 값으로 맞춘다 - 둘이 서로의 폴백이라 성격을 같게 둔다.
private const val MAX_CONTENT_LENGTH = 5000

// 긁어온 본문 텍스트의 하한. 이 길이에 못 미치면 "본문을 못 건졌다"로 보고 null을 돌려
// PostService.createPost / PostAiBackfillRunner의 폴백 엘비스가 발동하게 한다.
// 실측 근거(2026-09, 프로덕션 URL 직접 크롤링): 페이지 껍데기만 긁힌 사례가
// d2.naver.com 150자(네비게이션) / YouTube 358자(푸터) / tech.kakao.com 742자(네비+제목) /
// smartstore.naver.com 817자(봇 차단 안내문)였다. 가장 긴 껍데기(817)보다 위, 실제 기사
// 본문(수천 자)보다 한참 아래인 값으로 1,000을 잡는다.
private const val MIN_PAGE_CONTENT_LENGTH = 1000

// og:description·JSON-LD description·YouTube 영상 설명처럼 "사람이 직접 쓴" 값에 적용하는 하한.
// 본문 하한보다 훨씬 낮다 - 네비·푸터가 섞일 구조적 여지가 없어 짧아도 신호가 진짜이기 때문이다.
private const val MIN_META_DESCRIPTION_LENGTH = 40

// YouTube watch 페이지가 인라인 <script>에 심는 플레이어 상태 JSON의 시작 지점.
// videoDetails.shortDescription에 영상 설명 원문이 들어 있다(2026-09 실측 2,375자) -
// 이 페이지의 HTML 본문 텍스트는 푸터 358자뿐이라 이게 유일한 진짜 본문 소스다.
private val YT_PLAYER_RESPONSE = Regex("""ytInitialPlayerResponse\s*=\s*\{""")

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
    private val youtubeVideoClient: YoutubeVideoClient,
) {

    private val logger = LoggerFactory.getLogger(UrlMetadataExtractor::class.java)

    fun extract(url: String): UrlMetadata = try {
        // YouTube면 Data API를 1순위로 시도한다 - 성공하면 아래 스크래핑을 통째로
        // 건너뛴다. 실패(키 없음·videoId 파싱 불가·쿼터 초과·설명 하한 미달)하면
        // null이라 그대로 기존 스크래핑 경로로 떨어진다. 자세한 경위는
        // docs/AI-ASYNC-PROCESSING.md §5.7 참고.
        if (isYoutubeUrl(url)) {
            youtubeVideoClient.fetchSnippet(url)?.let { snippet -> toMetadata(url, snippet) }?.let { return it }
        }

        val response = safeConnect(url)
        val statusCode = response.statusCode()
        val metadata = parseMetadata(response.parse(), url, statusCode)

        // 2xx가 아니면 이 사이트가 이 요청을 명시적으로 거부했다는 뜻이다. 지금까지 이 분기는
        // 로그를 한 줄도 남기지 않아, techblog.woowahan.com 사고를 로그가 아니라 저장된 Post
        // row를 역추적해서야 알아냈다(docs/AI-ASYNC-PROCESSING.md §5.10). server 헤더까지
        // 남기는 이유는 그 사고의 결정적 증거가 `server: cloudflare`였기 때문이다 - 다음엔
        // curl 재현 없이 바로 원인 부류를 알 수 있다. (무료 공개 프록시로 재시도하는 시도는
        // 해봤으나 되돌렸다 - §5.10 "되돌린 이유" 참고.)
        if (statusCode !in 200..299) {
            logger.warn("[Crawling] 비정상 응답 - $url, status=$statusCode, server=${response.header("server")}")
        }

        var title = metadata.title
        var ogImage = metadata.ogImage
        // oEmbed는 폴백이다 - 제목·썸네일이 이미 og:*에서 나오면 왕복을 하나 더 태울 이유가 없다.
        // "빈약함" 판정은 PostService·PostAIService와 같은 WeakTitleDetector로 통일한다. 예전의
        // `title == url.take(100)`은 크롤링 실패 폴백만 잡아, 데이터센터 IP가 받는 껍데기 페이지의
        // <title> "- YouTube"를 정상 제목으로 오인했다(2026-09-08 실측, bodyTextLength=94).
        val titleIsWeak = WeakTitleDetector.isWeak(title, url)
        if (isYoutubeUrl(url) && (titleIsWeak || ogImage == null)) {
            val youtubeMeta = fetchYoutubeMetadata(url)
            if (youtubeMeta != null) {
                if (titleIsWeak && !youtubeMeta["title"].isNullOrBlank()) title = youtubeMeta["title"]!!
                if (ogImage == null && !youtubeMeta["thumbnail_url"].isNullOrBlank()) {
                    ogImage = youtubeMeta["thumbnail_url"]
                }
            }
        }

        // 크롤링 대상 사이트가 og:image를 http로 내리는 경우가 있다 - FE가 https로
        // 서빙되는 이상 그대로 저장하면 Mixed Content 경고가 뜨므로 저장 전에 정규화한다.
        metadata.copy(title = title, ogImage = ogImage?.replace(Regex("^http://"), "https://"))
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
        // YouTube watch 페이지는 제목·본문이 전부 이 인라인 JSON에 있다. 아래 제목·본문 양쪽에서
        // 쓰므로 70KB짜리 JSON을 두 번 파싱하지 않도록 여기서 한 번만 읽는다.
        val videoDetails = if (isYoutubeUrl(url)) youtubeVideoDetails(doc) else null

        val title = doc.select("meta[property=og:title]")
            .attr("content")
            .ifEmpty { videoDetails?.path("title")?.textValue().orEmpty() }
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
            pageContent = if (ok) resolvePageContent(doc, videoDetails, description, url) else null,
        )
    }

    /**
     * YoutubeVideoClient의 snippet을 UrlMetadata로 변환한다. 설명이 MIN_META_DESCRIPTION_LENGTH
     * 하한에 못 미치면 null을 돌려줘 호출부(extract)가 기존 스크래핑 경로로 떨어지게 한다.
     * description은 항상 null로 둔다 - 정상 시절 YouTube 글(운영 API 실측)도 description은
     * 늘 null이었고, 여기서 채우면 카드 UI가 달라지는 시각적 변경이 된다.
     */
    internal fun toMetadata(url: String, snippet: YoutubeSnippet): UrlMetadata? {
        val pageContent = snippet.description
            ?.let(::normalizeContent)
            ?.takeIf { it.length >= MIN_META_DESCRIPTION_LENGTH }
            ?: return null

        val host = URI(url).host.replace("www.", "")

        return UrlMetadata(
            title = snippet.title?.takeIf { it.isNotBlank() } ?: url.take(100),
            description = null,
            ogImage = snippet.thumbnailUrl?.replace(Regex("^http://"), "https://"),
            tags = listOfNotNull(host.takeIf { it.isNotEmpty() }),
            pageContent = pageContent,
        )
    }

    /**
     * 본문 소스를 우선순위대로 시도하고, 어느 것도 하한을 넘지 못하면 null을 돌려준다.
     * null이어야 PostService.kt의 `metadata.pageContent ?: fallbackContent` 엘비스와
     * PostAiBackfillRunner의 RSS 폴백이 발동한다 - 빈 문자열("")을 돌려주면 non-null이라
     * 그 폴백이 영영 안 걸린다(2026-09 이전의 결함).
     */
    private fun resolvePageContent(doc: Document, videoDetails: JsonNode?, ogDescription: String?, url: String): String? {
        // (1) YouTube: HTML 본문 텍스트가 푸터뿐이라 인라인 JSON의 영상 설명이 유일한 본문이다.
        videoDetails?.path("shortDescription")?.textValue()
            ?.let(::normalizeContent)
            ?.takeIf { it.length >= MIN_META_DESCRIPTION_LENGTH }
            ?.let { return it }

        // (2) 일반 페이지 본문.
        normalizeContent(doc.body().text())
            ?.takeIf { it.length >= MIN_PAGE_CONTENT_LENGTH }
            ?.let { return it }

        // (3) 본문이 껍데기(네비·푸터·봇 차단 안내)뿐일 때의 폴백. JSON-LD articleBody는 기사
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
     * YouTube watch 페이지의 인라인 스크립트에서 `var ytInitialPlayerResponse = {...};` 의
     * JSON 객체만 떼어낸다.
     *
     * `};`까지 정규식으로 잘라내는 방식은 쓰지 않는다 - 이 JSON은 실측 70KB가 넘고 중첩 객체와
     * 문자열 리터럴 안에도 `}`·`;`가 섞여 있어 어디서 끊길지 보장할 수 없다. 대신 여는 `{`의
     * 위치만 정규식으로 찾고, 그 지점부터 Jackson 파서에게 "JSON 값 하나만 읽으라"고 시킨다 -
     * readTree(JsonParser)는 트레일링 토큰을 검사하지 않으므로 뒤에 붙은 `;var meta = ...`는
     * 그대로 무시된다.
     *
     * 페이지에 `ytInitialPlayerResponse` 문자열은 3번 나오지만 `= {` 형태로 이어지는 건 첫
     * 번째뿐이다(나머지는 `window['ytInitialPlayerResponse']`, `a.ytInitialPlayerResponse`).
     */
    private fun youtubeVideoDetails(doc: Document): JsonNode? {
        for (element in doc.select("script")) {
            val data = element.data()
            // match.range.last는 정규식의 마지막 문자, 즉 여는 '{'의 인덱스다.
            val match = YT_PLAYER_RESPONSE.find(data) ?: continue
            val root: JsonNode? =
                runCatching {
                    objectMapper.factory.createParser(data.substring(match.range.last)).use { parser ->
                        objectMapper.readTree<JsonNode>(parser)
                    }
                }.getOrElse { e ->
                    // maxBodySize 상한에 걸려 HTML이 잘리면 JSON도 중간에서 끊긴다.
                    // 본문 없이 아래 폴백으로 내려가면 되므로 경고만 남긴다.
                    logger.warn("[Crawling] ytInitialPlayerResponse 파싱 실패", e)
                    null
                }
            return root?.get("videoDetails")
        }
        return null
    }

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
            currentUrl = applyLocaleOverride(currentUrl)
            safeUrlValidator.validate(currentUrl)
            val response =
                Jsoup.connect(currentUrl)
                    .userAgent(USER_AGENT)
                    .referrer("http://google.com")
                    .header("Accept-Language", ACCEPT_LANGUAGE)
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

    /**
     * [LOCALE_OVERRIDE_HOSTS]에 있는 호스트면 경로 첫 세그먼트에 언어 접두어를 강제로 끼워
     * 넣는다. safeConnect가 매 홉(최초 요청 포함)마다 호출한다 - 그 사이트의 리다이렉트가
     * 접두어를 지우고 되돌리더라도(인프런 dashboard 서브경로 실측) 다음 홉에서 다시 붙는다.
     * 네트워크 없이 검증할 수 있도록 internal로 분리했다 - parseMetadata·toMetadata와 같은 이유.
     */
    internal fun applyLocaleOverride(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.removePrefix("www.") ?: return url
        val locale = LOCALE_OVERRIDE_HOSTS[host] ?: return url

        val firstSegment = uri.path.removePrefix("/").substringBefore("/")
        if (firstSegment in KNOWN_LOCALE_SEGMENTS) return url

        return URI(uri.scheme, uri.authority, "/$locale${uri.path}", uri.query, uri.fragment).toString()
    }

    private fun isYoutubeUrl(url: String) = url.contains("youtube.com") || url.contains("youtu.be")

    private fun fetchYoutubeMetadata(url: String): Map<String, String>? = try {
        // url을 그대로 문자열 보간하면 `?si=` 같은 추적 파라미터의 `&`가 oEmbed 쿼리 자체를 쪼갠다.
        val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8)
        val oembedUrl = "https://www.youtube.com/oembed?url=$encodedUrl&format=json"
        // 타임아웃을 안 주면 Jsoup 기본값 30초다 - 등록 요청 경로에서 그만큼 매달릴 수 있어
        // safeConnect와 같은 5초로 맞춘다.
        val json = Jsoup.connect(oembedUrl).ignoreContentType(true).timeout(5000).execute().body()
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
