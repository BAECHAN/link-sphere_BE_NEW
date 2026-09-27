package com.example.linksphere.domain.post

import com.example.linksphere.infra.youtube.YoutubeVideoClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.net.InetSocketAddress

/**
 * safeConnect가 실제로 보내는 요청 헤더를 검증한다. 로컬 HttpServer를 띄우고 붙어서, 목이 아니라
 * 진짜 소켓 요청을 관찰한다 - UrlMetadataExtractorTest의 parseMetadata 검증(네트워크 없는 순수
 * 함수)과 성격이 달라 별도 파일로 뒀다.
 *
 * SafeUrlValidator는 실제 구현을 쓰면 127.0.0.1이 loopback이라 거부되므로 목으로 통과시킨다
 * (UrlMetadataExtractorTest와 같은 이유).
 */
class SafeConnectTest {

    private lateinit var server: HttpServer

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun startServer(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): String {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        return "http://127.0.0.1:${server.address.port}"
    }

    @Test
    fun `Accept-Language 헤더를 ko-KR 우선으로 보낸다`() {
        var receivedAcceptLanguage: String? = null
        val baseUrl =
            startServer { exchange ->
                receivedAcceptLanguage = exchange.requestHeaders.getFirst("Accept-Language")
                val body = "<html><body></body></html>".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

        val extractor =
            UrlMetadataExtractor(
                ObjectMapper(),
                mock(SafeUrlValidator::class.java),
                mock(YoutubeVideoClient::class.java),
                crawlProxyUrlPrefix = "",
            )
        extractor.safeConnect(baseUrl)

        assertTrue(receivedAcceptLanguage?.startsWith("ko-KR") == true, "actual=$receivedAcceptLanguage")
    }
}
