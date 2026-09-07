package com.example.linksphere.domain.post

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class UrlMetadataExtractorTest {

    // parseMetadata는 네트워크를 타지 않는 순수 함수라 SafeUrlValidator는 목으로만 채워둔다
    // (FeedParserTest가 UrlMetadataExtractor를 목으로 채우는 것과 같은 이유).
    private val extractor = UrlMetadataExtractor(ObjectMapper(), mock(SafeUrlValidator::class.java))

    // baseUri를 넘겨야 og:image 상대경로의 abs: 절대화가 실제 코드와 같은 조건에서 검증된다.
    private fun parse(
        html: String,
        url: String = "https://example.com/a",
        status: Int = 200,
    ) = extractor.parseMetadata(Jsoup.parse(html, url), url, status)

    @Test
    fun `본문이 비면 빈 문자열이 아니라 null을 돌려준다`() {
        val metadata = parse("<html><body></body></html>")

        assertNull(metadata.pageContent)
    }

    @Test
    fun `본문이 하한에 못 미치면 null로 떨군다`() {
        // YouTube 푸터 실측(358자) 수준의 짧은 본문.
        val metadata = parse("<html><body><p>${"가".repeat(358)}</p></body></html>")

        assertNull(metadata.pageContent)
    }

    @Test
    fun `본문이 하한을 넘으면 그대로 쓴다`() {
        val body = "가".repeat(1200)
        val metadata = parse("<html><body><p>$body</p></body></html>")

        assertEquals(1200, metadata.pageContent!!.length)
    }

    @Test
    fun `본문은 공백을 접고 5000자로 자른다`() {
        val body = (1..6000).joinToString("\n") { "나" }
        val metadata = parse("<html><body><p>$body</p></body></html>")

        assertEquals(5000, metadata.pageContent!!.length)
        assertEquals(false, metadata.pageContent!!.contains("\n"))
    }

    @Test
    fun `videoDetails가 없는 YouTube 페이지는 본문 폴백으로 내려간다`() {
        // YouTube 본문 추출(ytInitialPlayerResponse)은 아직 없다 - 일반 페이지와 동일하게
        // 본문 하한만으로 판정한다. 푸터 실측(358자)이 하한 미달이라 null이 된다.
        val html = "<html><body><footer>${"바".repeat(358)}</footer></body></html>"

        val metadata = parse(html, url = "https://youtu.be/abc123")

        assertNull(metadata.pageContent)
    }

    @Test
    fun `본문이 하한 미달이면 JSON-LD articleBody를 쓴다`() {
        val articleBody = "아".repeat(500)
        val html =
            """
            <html><body>
            <script type="application/ld+json">{"@type":"BlogPosting","articleBody":"$articleBody"}</script>
            <nav>${"자".repeat(150)}</nav>
            </body></html>
            """.trimIndent()

        val metadata = parse(html)

        assertEquals(articleBody, metadata.pageContent)
    }

    @Test
    fun `JSON-LD가 배열이나 @graph로 감싸져도 찾는다`() {
        val articleBody = "차".repeat(500)
        val html =
            """
            <html><body>
            <script type="application/ld+json">{"@graph":[{"@type":"BlogPosting","articleBody":"$articleBody"}]}</script>
            </body></html>
            """.trimIndent()

        val metadata = parse(html)

        assertEquals(articleBody, metadata.pageContent)
    }

    @Test
    fun `본문·JSON-LD가 없으면 og_description을 본문으로 쓴다`() {
        val description = "타".repeat(120)
        val html = """<html><head><meta property="og:description" content="$description"></head><body></body></html>"""

        val metadata = parse(html)

        assertEquals(description, metadata.pageContent)
    }

    @Test
    fun `og_description이 메타 하한 미만이면 null이다`() {
        val html = """<html><head><meta property="og:description" content="${"파".repeat(20)}"></head><body></body></html>"""

        val metadata = parse(html)

        assertNull(metadata.pageContent)
    }

    @Test
    fun `2xx가 아니면 title 태그를 제목으로 쓰지 않는다`() {
        val html = "<html><head><title>Just a moment...</title></head><body></body></html>"
        val url = "https://stackoverflow.com/questions/1"

        val metadata = parse(html, url = url, status = 403)

        assertEquals(url.take(100), metadata.title)
    }

    @Test
    fun `2xx가 아니어도 og_title은 제목으로 쓴다`() {
        val html = """<html><head><meta property="og:title" content="정상 제목"></head><body></body></html>"""

        val metadata = parse(html, status = 403)

        assertEquals("정상 제목", metadata.title)
    }

    @Test
    fun `2xx가 아니면 pageContent는 항상 null이다`() {
        val html = "<html><body><p>${"하".repeat(2000)}</p></body></html>"

        val metadata = parse(html, status = 403)

        assertNull(metadata.pageContent)
    }

    @Test
    fun `제목 폴백은 100자로 절삭한다`() {
        val url = "https://example.com/" + "a".repeat(150)

        val metadata = parse("<html><body></body></html>", url = url)

        assertEquals(100, metadata.title.length)
    }

    @Test
    fun `og_image 상대경로는 baseUri 기준 절대 URL이 된다`() {
        val html = """<html><head><meta property="og:image" content="/img/t.png"></head><body></body></html>"""

        val metadata = parse(html, url = "https://example.com/a")

        assertEquals("https://example.com/img/t.png", metadata.ogImage)
    }

    @Test
    fun `tags에 www를 뗀 호스트가 들어간다`() {
        val metadata = parse("<html><body></body></html>", url = "https://www.example.com/a")

        assertEquals(listOf("example.com"), metadata.tags)
    }
}
