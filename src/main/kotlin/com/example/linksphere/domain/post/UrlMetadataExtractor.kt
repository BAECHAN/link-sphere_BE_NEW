package com.example.linksphere.domain.post

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI

private const val USER_AGENT =
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MAX_REDIRECTS = 5

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
        val doc = safeConnect(url).parse()

        var title =
            doc.select("meta[property=og:title]")
                .attr("content")
                .ifEmpty { doc.title() }
                .ifEmpty { url }
        val description = doc.select("meta[property=og:description]").attr("content").ifEmpty { null }
        var ogImage = doc.select("meta[property=og:image]").attr("content").ifEmpty { null }

        val tags = mutableListOf<String>()
        val host = java.net.URI(url).host.replace("www.", "")
        if (host.isNotEmpty()) tags.add(host)

        val pageContent = doc.body().text().replace("\\s+".toRegex(), " ").trim().take(5000)

        if (isYoutubeUrl(url)) {
            val youtubeMeta = fetchYoutubeMetadata(url)
            if (youtubeMeta != null) {
                if (!youtubeMeta["title"].isNullOrBlank()) title = youtubeMeta["title"]!!
                if (ogImage == null && !youtubeMeta["thumbnail_url"].isNullOrBlank()) {
                    ogImage = youtubeMeta["thumbnail_url"]
                }
            }
        }

        UrlMetadata(
            title = title,
            description = description,
            ogImage = ogImage,
            tags = tags,
            pageContent = pageContent,
        )
    } catch (e: Exception) {
        logger.error("[Crawling] 크롤링 실패: $url", e)
        UrlMetadata(title = url, description = null, ogImage = null, tags = emptyList(), pageContent = null)
    }

    /**
     * Jsoup의 자동 리다이렉트를 끄고 직접 따라가면서, 매 홉마다 SafeUrlValidator로 재검증한다.
     * 공개 URL이 응답에서 사설 IP로 리다이렉트하는 SSRF 우회를 막기 위함이다.
     */
    private fun safeConnect(url: String): org.jsoup.Connection.Response {
        var currentUrl = url
        var hop = 0
        while (true) {
            safeUrlValidator.validate(currentUrl)
            val response =
                Jsoup.connect(currentUrl)
                    .userAgent(USER_AGENT)
                    .referrer("http://google.com")
                    .timeout(5000)
                    .followRedirects(false)
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
