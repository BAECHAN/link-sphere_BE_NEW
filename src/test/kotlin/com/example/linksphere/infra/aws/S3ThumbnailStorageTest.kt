package com.example.linksphere.infra.aws

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.InetSocketAddress
import java.net.URI

/**
 * S3에 실제로 보내는 요청(메서드·키·형식·캐시 헤더)을 본다. 로컬 HttpServer를 S3 주소로 두고 받은 요청을 기록한다 - 실제 AWS·자격
 * 증명 없이 돈다(MailServiceTest와 같은 이유). 운영 버킷(HTTPS, 서울)에 실제로 쓰이는지는 배포 뒤 첫 소급 호출로 확인한다.
 */
class S3ThumbnailStorageTest {

    private lateinit var server: HttpServer
    private val received = mutableListOf<Received>()

    data class Received(val method: String, val path: String, val contentType: String?, val cacheControl: String?, val body: ByteArray)

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    @Test
    fun `put은 접두어를 붙인 키에 형식과 캐시 헤더를 달아 올리고 공개 주소를 돌려준다`() {
        val storage = storage()
        val bytes = "jpeg-bytes".toByteArray()

        val url = storage.put("post-abc.jpg", bytes, "image/jpeg", "public, max-age=31536000, immutable")

        assertEquals("https://linksphere.test/og-thumbnails/post-abc.jpg", url)
        val put = received.single()
        assertEquals("PUT", put.method)
        assertEquals("/link-sphere-fe-bucket/og-thumbnails/post-abc.jpg", put.path)
        assertEquals("image/jpeg", put.contentType)
        assertEquals("public, max-age=31536000, immutable", put.cacheControl)
        assertTrue(String(put.body).contains("jpeg-bytes"), String(put.body))
    }

    @Test
    fun `delete는 같은 키를 지운다`() {
        storage().delete("post-abc.png")

        val delete = received.single()
        assertEquals("DELETE", delete.method)
        assertEquals("/link-sphere-fe-bucket/og-thumbnails/post-abc.png", delete.path)
    }

    private fun storage(): S3ThumbnailStorage {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            received +=
                Received(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.path,
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    cacheControl = exchange.requestHeaders.getFirst("Cache-Control"),
                    body = exchange.requestBody.readAllBytes(),
                )
            exchange.sendResponseHeaders(if (exchange.requestMethod == "DELETE") 204 else 200, -1)
            exchange.close()
        }
        server.start()

        val endpoint = URI("http://127.0.0.1:${server.address.port}")
        return S3ThumbnailStorage(
            bucket = "link-sphere-fe-bucket",
            keyPrefix = "og-thumbnails/",
            publicBaseUrl = "https://linksphere.test/og-thumbnails/",
        ) {
            S3Client.builder()
                .region(Region.AP_NORTHEAST_2)
                .endpointOverride(endpoint)
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build()
        }
    }
}
