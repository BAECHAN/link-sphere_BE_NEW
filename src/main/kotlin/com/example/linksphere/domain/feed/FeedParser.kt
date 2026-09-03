package com.example.linksphere.domain.feed

import com.example.linksphere.domain.post.UrlMetadataExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.springframework.stereotype.Component

/** RSS 2.0 / Atom 피드 항목 하나 - 게시글로 등록하기 전의 원시 (제목, 링크). */
data class FeedEntry(val title: String, val link: String)

@Component
class FeedParser(
    private val urlMetadataExtractor: UrlMetadataExtractor,
) {

    /**
     * [feedUrl]을 가져와 파싱한다. fetch는 UrlMetadataExtractor.safeConnect를 재사용해
     * 리다이렉트 홉마다 SSRF 재검증이 피드 URL에도 예외 없이 적용되게 한다.
     */
    fun fetch(feedUrl: String): List<FeedEntry> {
        val xml = urlMetadataExtractor.safeConnect(feedUrl).body()
        return parse(xml)
    }

    /** 네트워크 없이 파싱만 검증할 수 있도록 분리한 순수 함수. */
    fun parse(xml: String): List<FeedEntry> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())

        val rssItems = doc.select("item")
        if (rssItems.isNotEmpty()) {
            return rssItems.mapNotNull(::toRssEntry)
        }

        return doc.select("entry").mapNotNull(::toAtomEntry)
    }

    private fun toRssEntry(item: Element): FeedEntry? {
        val title = item.selectFirst("title")?.text()?.trim().orEmpty()
        val link = item.selectFirst("link")?.text()?.trim().orEmpty()
        return if (title.isNotEmpty() && link.isNotEmpty()) FeedEntry(title, link) else null
    }

    // Atom은 링크가 텍스트가 아니라 <link href="..."> 속성에 있고, rel별로 여러 개(alternate/self 등)일
    // 수 있다. rel이 없거나 "alternate"인 것을 원문 링크로 취급한다.
    private fun toAtomEntry(entry: Element): FeedEntry? {
        val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
        val link =
            entry.select("link")
                .firstOrNull { it.attr("rel").let { rel -> rel.isEmpty() || rel == "alternate" } }
                ?.attr("href")
                ?.trim()
                .orEmpty()
        return if (title.isNotEmpty() && link.isNotEmpty()) FeedEntry(title, link) else null
    }
}
