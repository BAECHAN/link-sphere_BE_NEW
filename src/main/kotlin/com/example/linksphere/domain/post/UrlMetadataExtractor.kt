package com.example.linksphere.domain.post

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class UrlMetadata(
        val title: String,
        val description: String?,
        val ogImage: String?,
        val tags: List<String>,
        val pageContent: String?
)

@Component
class UrlMetadataExtractor(private val objectMapper: ObjectMapper) {

    private val logger = LoggerFactory.getLogger(UrlMetadataExtractor::class.java)

    fun extract(url: String): UrlMetadata {
        return try {
            val doc =
                    Jsoup.connect(url)
                            .userAgent(
                                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                            .referrer("http://google.com")
                            .timeout(5000)
                            .get()

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
                    pageContent = pageContent
            )
        } catch (e: Exception) {
            logger.error("[Crawling] 크롤링 실패: $url", e)
            UrlMetadata(title = url, description = null, ogImage = null, tags = emptyList(), pageContent = null)
        }
    }

    private fun isYoutubeUrl(url: String) = url.contains("youtube.com") || url.contains("youtu.be")

    private fun fetchYoutubeMetadata(url: String): Map<String, String>? {
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=$url&format=json"
            val json = Jsoup.connect(oembedUrl).ignoreContentType(true).execute().body()
            val node = objectMapper.readTree(json)
            mapOf(
                    "title" to (node.get("title")?.asText() ?: ""),
                    "thumbnail_url" to (node.get("thumbnail_url")?.asText() ?: "")
            )
        } catch (e: Exception) {
            logger.warn("Failed to fetch YouTube oEmbed data for: $url", e)
            null
        }
    }
}
