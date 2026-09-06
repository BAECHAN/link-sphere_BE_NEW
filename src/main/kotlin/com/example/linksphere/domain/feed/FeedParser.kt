package com.example.linksphere.domain.feed

import com.example.linksphere.domain.post.UrlMetadataExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import org.springframework.stereotype.Component

/** RSS 2.0 / Atom 피드 항목 하나 - 게시글로 등록하기 전의 원시 (제목, 링크, 본문). */
data class FeedEntry(val title: String, val link: String, val content: String? = null)

@Component
class FeedParser(
    private val urlMetadataExtractor: UrlMetadataExtractor,
) {

    companion object {
        // UrlMetadataExtractor.kt:44가 크롤링 본문(pageContent)에 적용하는 상한과 동일하게 맞춘다 -
        // 이 값이 그 pageContent 대신 쓰이는 폴백이라 성격을 같게 둔다. self-invoke JSON payload에
        // 그대로 실리므로(Lambda 비동기 호출 상한 256KB) 큰 값으로 올릴 때는 CHUNK_SIZE와 함께 검토할 것.
        private const val MAX_CONTENT_LENGTH = 5000
    }

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
        // Jsoup 셀렉터는 네임스페이스 구분자로 ':' 대신 '|'를 쓴다 - <content:encoded>를 고르려면
        // "content|encoded". 태그명 자체가 "content:encoded"라 아래 Atom의 select("content")와는
        // 겹치지 않는다.
        val content = item.selectFirst("content|encoded")?.text()?.let(::toPlainText)
        return if (title.isNotEmpty() && link.isNotEmpty()) FeedEntry(title, link, content) else null
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
        val content = entry.selectFirst("content")?.text()?.let(::toPlainText)
        return if (title.isNotEmpty() && link.isNotEmpty()) FeedEntry(title, link, content) else null
    }

    // content:encoded / Atom <content>는 CDATA 또는 이스케이프된 HTML 문자열이다. xmlParser는 이를
    // 텍스트로만 보므로, HTML 파서로 한 번 더 파싱해야 태그가 벗겨진 평문이 나온다. 정규화 방식은
    // UrlMetadataExtractor.kt:44(크롤링 본문에 적용)와 동일하게 맞춘다.
    private fun toPlainText(rawHtml: String): String? = Jsoup.parse(rawHtml).body().text()
        .replace("\\s+".toRegex(), " ")
        .trim()
        .take(MAX_CONTENT_LENGTH)
        .ifEmpty { null }
}
