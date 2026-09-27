package com.example.linksphere.domain.post

import com.example.linksphere.infra.youtube.YoutubeVideoClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * extract()의 non-2xx → 프록시 폴백 분기를 실제 소켓 요청으로 검증한다. SafeConnectTest와
 * 같은 이유로 로컬 HttpServer를 쓴다 - 목이 아니라 진짜 왕복 2개(대상·프록시)를 관찰한다.
 */
class CrawlProxyFallbackTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private val proxyHitCount = AtomicInteger(0)

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    /**
     * `/target`은 항상 403 + 빈 본문을 준다(예: techblog.woowahan.com이 도쿄 Lambda에게
     * 준 것과 같은 모양). `/proxy`는 [proxyStatus]와 [proxyBody]를 그대로 주고, 호출 횟수를
     * [proxyHitCount]에 기록한다.
     */
    private fun startServer(proxyStatus: Int = 200, proxyBody: String = successHtml()) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/target") { exchange ->
            val body = ByteArray(0)
            exchange.sendResponseHeaders(403, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/proxy") { exchange ->
            proxyHitCount.incrementAndGet()
            val body = proxyBody.toByteArray()
            exchange.sendResponseHeaders(proxyStatus, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    private fun successHtml() = """<html><head><meta property="og:title" content="프록시로 받은 제목"><meta property="og:description" content="프록시로 받은 설명"></head><body><p>${"가".repeat(1200)}</p></body></html>"""

    private fun extractor(crawlProxyUrlPrefix: String) = UrlMetadataExtractor(
        ObjectMapper(),
        mock(SafeUrlValidator::class.java),
        mock(YoutubeVideoClient::class.java),
        crawlProxyUrlPrefix = crawlProxyUrlPrefix,
    )

    @Test
    fun `대상이 403이고 allowProxyFallback이 true면 프록시로 재시도해 채택한다`() {
        startServer()
        val targetUrl = "$baseUrl/target"
        val extractor = extractor(crawlProxyUrlPrefix = "$baseUrl/proxy?url=")

        val metadata = extractor.extract(targetUrl, allowProxyFallback = true)

        assertEquals("프록시로 받은 제목", metadata.title)
        assertEquals("프록시로 받은 설명", metadata.description)
        assertEquals(1, proxyHitCount.get())
    }

    @Test
    fun `allowProxyFallback이 false면 대상이 403이어도 프록시를 타지 않는다`() {
        startServer()
        val targetUrl = "$baseUrl/target"
        val extractor = extractor(crawlProxyUrlPrefix = "$baseUrl/proxy?url=")

        val metadata = extractor.extract(targetUrl, allowProxyFallback = false)

        assertEquals(targetUrl.take(100), metadata.title)
        assertNull(metadata.description)
        assertEquals(0, proxyHitCount.get())
    }

    @Test
    fun `프록시 자신도 실패하면 예외 없이 기존 동작으로 내려간다`() {
        startServer(proxyStatus = 500, proxyBody = "")
        val targetUrl = "$baseUrl/target"
        val extractor = extractor(crawlProxyUrlPrefix = "$baseUrl/proxy?url=")

        val metadata = extractor.extract(targetUrl, allowProxyFallback = true)

        assertEquals(targetUrl.take(100), metadata.title)
        assertNull(metadata.description)
        assertEquals(1, proxyHitCount.get())
    }

    @Test
    fun `프록시 주소가 빈 문자열(킬스위치)이면 프록시를 타지 않는다`() {
        startServer()
        val targetUrl = "$baseUrl/target"
        val extractor = extractor(crawlProxyUrlPrefix = "")

        val metadata = extractor.extract(targetUrl, allowProxyFallback = true)

        assertEquals(targetUrl.take(100), metadata.title)
        assertEquals(0, proxyHitCount.get())
    }

    @Test
    fun `대상이 2xx면 본문이 부실해도 프록시를 타지 않는다`() {
        // 발동 조건은 non-2xx뿐이다 - JS 렌더링 사이트 같은 "200+부실 본문"까지 넓히면 매번
        // 헛수고 왕복이 생긴다(docs/AI-ASYNC-PROCESSING.md §5.10, 확정된 발동 범위 참고).
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/target") { exchange ->
            val body = "<html><body></body></html>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/proxy") { exchange ->
            proxyHitCount.incrementAndGet()
            val body = successHtml().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        val targetUrl = "$baseUrl/target"
        val extractor = extractor(crawlProxyUrlPrefix = "$baseUrl/proxy?url=")

        val metadata = extractor.extract(targetUrl, allowProxyFallback = true)

        assertNull(metadata.pageContent)
        assertEquals(0, proxyHitCount.get())
    }

    @Test
    fun `프록시 요청은 원본 URL을 인코딩해 넘긴다`() {
        var receivedQuery: String? = null
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/target") { exchange ->
            exchange.sendResponseHeaders(403, 0)
            exchange.responseBody.close()
        }
        server.createContext("/proxy") { exchange ->
            receivedQuery = exchange.requestURI.rawQuery
            val body = successHtml().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
        val targetUrl = "$baseUrl/target?a=1&b=2"
        val extractor = extractor(crawlProxyUrlPrefix = "$baseUrl/proxy?url=")

        extractor.extract(targetUrl, allowProxyFallback = true)

        val expected = "url=" + URLEncoder.encode(targetUrl, StandardCharsets.UTF_8)
        assertEquals(expected, receivedQuery)
    }
}
