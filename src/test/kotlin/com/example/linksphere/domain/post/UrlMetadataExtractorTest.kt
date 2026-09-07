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
    fun `YouTube 인라인 JSON에서 영상 설명을 본문으로 뽑는다`() {
        val description = "다".repeat(300)
        val html =
            """
            <html><body>
            <script>var ytInitialPlayerResponse = {"videoDetails":{"title":"영상 제목","shortDescription":"$description"}};var meta=1;</script>
            <footer>${"라".repeat(358)}</footer>
            </body></html>
            """.trimIndent()

        val metadata = parse(html, url = "https://youtu.be/abc123")

        assertEquals(description, metadata.pageContent)
        assertEquals("영상 제목", metadata.title)
    }

    @Test
    fun `JSON 뒤에 붙은 스크립트 코드는 파싱에 영향을 주지 않는다`() {
        // 설명 본문 안에 닫는 중괄호·세미콜론과 비슷한 문자열이 섞여 있어도, 여는 '{' 위치부터
        // Jackson이 값 하나만 읽으므로 전체 설명이 온전히 나와야 한다(단순 정규식 절단이면 깨진다).
        // "</script"는 JS 문자열 안에 있어도 HTML 파서가 그 자리에서 스크립트 블록을 끊어버리므로
        // (실제 브라우저도 동일하게 동작 - YouTube 원본은 "<\/script"로 이스케이프해 이를 피한다)
        // 이 테스트에서는 넣지 않는다. 여기서 검증하는 건 어디까지나 중첩 중괄호/세미콜론 처리다.
        val description = "설명 중간에 }; 와 중첩된 {객체} 문자열이 섞여 있어도 " + "마".repeat(300)
        val html =
            """
            <html><body>
            <script>var ytInitialPlayerResponse = {"videoDetails":{"title":"제목","shortDescription":"$description"}};var meta=document.createElement('meta');</script>
            </body></html>
            """.trimIndent()

        val metadata = parse(html, url = "https://www.youtube.com/watch?v=abc123")

        assertEquals(description, metadata.pageContent)
    }

    @Test
    fun `videoDetails가 없는 YouTube 페이지는 본문 폴백으로 내려간다`() {
        val html = "<html><body><footer>${"바".repeat(358)}</footer></body></html>"

        val metadata = parse(html, url = "https://youtu.be/abc123")

        assertNull(metadata.pageContent)
    }

    @Test
    fun `ytInitialPlayerResponse는 대입 형태인 첫 번째만 읽는다`() {
        val description = "사".repeat(300)
        val html =
            """
            <html><body>
            <script>console.log(window['ytInitialPlayerResponse']);</script>
            <script>var ytInitialPlayerResponse = {"videoDetails":{"title":"실제 제목","shortDescription":"$description"}};</script>
            </body></html>
            """.trimIndent()

        val metadata = parse(html, url = "https://youtu.be/abc123")

        assertEquals(description, metadata.pageContent)
        assertEquals("실제 제목", metadata.title)
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
