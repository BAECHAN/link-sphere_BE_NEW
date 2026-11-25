package com.example.linksphere.domain.linkpreview

import com.example.linksphere.infra.youtube.YoutubeVideoClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.Mockito.mock
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

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
            UrlMetadataExtractor(ObjectMapper(), mock(SafeUrlValidator::class.java), mock(YoutubeVideoClient::class.java))
        extractor.safeConnect(baseUrl)

        assertTrue(receivedAcceptLanguage?.startsWith("ko-KR") == true, "actual=$receivedAcceptLanguage")
    }

    // 사본 잡의 원본 받기(OgThumbnailService). 시간 한도는 연결부터 본문 끝까지 합친 값이라야 한다 - 한 바이트씩 흘려 보내는 서버가
    // 읽기마다 한도 안에 들어와도 잡이 끝나지 않으면 안 된다. 합친 값이 아니면 이 테스트는 @Timeout(10초)에 걸린다
    @Test
    @Timeout(10, unit = TimeUnit.SECONDS)
    fun `safeConnectImage는 본문이 시간 한도 안에 다 오지 않으면 SocketTimeoutException을 던진다`() {
        val baseUrl =
            startServer { exchange ->
                exchange.responseHeaders.add("Content-Type", "image/png")
                exchange.sendResponseHeaders(200, 100)
                exchange.responseBody.use { out ->
                    repeat(100) {
                        out.write(0)
                        out.flush()
                        Thread.sleep(200)
                    }
                }
            }

        val extractor =
            UrlMetadataExtractor(ObjectMapper(), mock(SafeUrlValidator::class.java), mock(YoutubeVideoClient::class.java))

        assertThrows(SocketTimeoutException::class.java) {
            extractor.safeConnectImage(baseUrl, maxBodyBytes = 1024, timeoutMillis = 1000).bodyAsBytes()
        }
    }

    // safeConnect의 4MB 상한처럼 넘으면 조용히 잘린다 - 호출하는 쪽이 상한+1을 넘겨 "넘었는지"를 길이로 가린다
    @Test
    fun `safeConnectImage는 본문을 상한에서 자르고 이미지 Content-Type을 그대로 돌려준다`() {
        val baseUrl =
            startServer { exchange ->
                val body = ByteArray(5000)
                exchange.responseHeaders.add("Content-Type", "image/jpeg")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }

        val extractor =
            UrlMetadataExtractor(ObjectMapper(), mock(SafeUrlValidator::class.java), mock(YoutubeVideoClient::class.java))
        val response = extractor.safeConnectImage(baseUrl, maxBodyBytes = 1001, timeoutMillis = 5000)

        assertEquals("image/jpeg", response.contentType())
        assertEquals(1001, response.bodyAsBytes().size)
    }
}
