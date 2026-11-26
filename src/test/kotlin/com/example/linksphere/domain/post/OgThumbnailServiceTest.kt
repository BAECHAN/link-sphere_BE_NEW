package com.example.linksphere.domain.post

import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.global.job.JobPublisher
import com.example.linksphere.global.storage.ThumbnailStorage
import com.example.linksphere.infra.youtube.YoutubeVideoClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.MessageDigest
import java.util.Random
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * 사본 잡의 받기·저장·컬럼 갱신과 커밋 뒤 리스너를 본다. 원본은 로컬 HttpServer가 내주고(SafeConnectTest와 같은 방식), 받기는
 * 실제 UrlMetadataExtractor를 거친다. 검증기는 테스트 서버(127.0.0.1)만 통과시키고 나머지는 실제 SafeUrlValidator 판정을 쓴다 -
 * 사설 IP로 가는 리다이렉트가 정말 막히는지 보려는 것이다.
 */
class OgThumbnailServiceTest {

    private var server: HttpServer? = null
    private val requests = AtomicInteger()

    private val postRepository = mock(PostRepository::class.java)
    private val jobPublisher = mock(JobPublisher::class.java)
    private val storage = FakeThumbnailStorage()

    private val validator =
        object : SafeUrlValidator() {
            override fun validate(url: String) {
                if (URI(url).host == "127.0.0.1") {
                    return
                }

                super.validate(url)
            }
        }
    private val extractor = UrlMetadataExtractor(ObjectMapper(), validator, mock(YoutubeVideoClient::class.java))

    private val postId = UUID.fromString("00000000-0000-4000-8000-0000000000a1")

    @AfterEach
    fun tearDown() {
        server?.stop(0)
    }

    @Test
    fun `받은 이미지를 줄여 og-thumbnails 이름으로 올리고 원본 주소가 그대로일 때만 컬럼을 채운다`() {
        val ogImage = serve(contentType = "image/png", body = png(3200, 2160))
        val name = "$postId-${sha256Hex(ogImage).take(12)}.jpg"
        val url = "https://cdn.test/og-thumbnails/$name"
        `when`(postRepository.updateOgImageThumbnailIfOgImage(postId, ogImage, url)).thenReturn(1)

        val result = service().createThumbnail(postId, ogImage)

        assertEquals(OgThumbnailResult.Stored(url), result)
        val put = storage.puts.single()
        assertEquals(name, put.name)
        assertEquals("image/jpeg", put.contentType)
        assertEquals("public, max-age=31536000, immutable", put.cacheControl)
        assertEquals(960 to 648, ImageIO.read(put.bytes.inputStream()).let { it.width to it.height })
        verify(postRepository).updateOgImageThumbnailIfOgImage(postId, ogImage, url)
    }

    // 늦게 온 옛 잡: 글 주소가 바뀌어 og_image가 달라졌거나 글이 지워져 조건부 UPDATE가 0행이다.
    // 원본은 잡음 그림이다 - 단색 PNG는 아주 작게 압축돼 줄인 JPEG가 더 커서(not-smaller) 올리기 전에 끝난다
    @Test
    fun `잡이 도는 사이 원본 주소가 바뀌면 올린 사본을 지우고 컬럼을 건드리지 않는다`() {
        val ogImage = serve(contentType = "image/png", body = pngOf(noisy(1280, 720)))

        val result = service().createThumbnail(postId, ogImage)

        assertEquals(OgThumbnailResult.NotStored("post-changed"), result)
        assertEquals(listOf(storage.puts.single().name), storage.deletes)
    }

    // 이미 작게 압축된 원본(YouTube hqdefault 480×360 등)은 줄일 픽셀이 없어 다시 인코딩만 하면 원본보다 커진다.
    // 다시 판단받는 글(소급 재실행)에 그 이름의 옛 사본이 남아 있을 수 있어 두 확장자 이름을 지운다
    @Test
    fun `다시 인코딩한 결과가 원본보다 작지 않으면 올리지 않고 같은 이름의 옛 사본을 지운다`() {
        val source = jpeg(noisy(480, 360), quality = 0.3f)
        val resized = OgThumbnailResizer.resize(source) as OgThumbnailResizer.Resized
        assertTrue(resized.bytes.size >= source.size, "고정물 전제: 다시 인코딩하면 원본보다 커야 한다")
        val ogImage = serve(contentType = "image/jpeg", body = source)
        val hash = sha256Hex(ogImage).take(12)

        val result = service().createThumbnail(postId, ogImage)

        assertEquals(OgThumbnailResult.NotStored("not-smaller"), result)
        assertEquals(emptyList<FakeThumbnailStorage.Put>(), storage.puts)
        assertEquals(listOf("$postId-$hash.jpg", "$postId-$hash.png"), storage.deletes)
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `8MB를 넘는 원본은 사본을 만들지 않는다`() {
        val ogImage = serve(contentType = "image/png", body = ByteArray(8 * 1024 * 1024 + 1))

        assertEquals(OgThumbnailResult.NotStored("too-large"), service().createThumbnail(postId, ogImage))
        assertEquals(emptyList<FakeThumbnailStorage.Put>(), storage.puts)
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `8MB 정확히는 받는다`() {
        val image = png(1920, 1080)
        val ogImage = serve(contentType = "image/png", body = image + ByteArray(8 * 1024 * 1024 - image.size))

        val result = service().createThumbnail(postId, ogImage)

        assertEquals(OgThumbnailResult.NotStored("post-changed"), result)
    }

    @Test
    fun `image가 아닌 응답이면 사본을 만들지 않는다`() {
        val ogImage = serve(contentType = "text/html; charset=utf-8", body = "<html></html>".toByteArray())

        assertEquals(OgThumbnailResult.NotStored("not-image"), service().createThumbnail(postId, ogImage))
        assertEquals(emptyList<FakeThumbnailStorage.Put>(), storage.puts)
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `원본이 2xx가 아니면 사본을 만들지 않는다`() {
        val ogImage = serve(contentType = "image/png", body = png(10, 10), status = 404)

        assertEquals(OgThumbnailResult.NotStored("status-404"), service().createThumbnail(postId, ogImage))
    }

    // 공개 주소가 응답에서 내부 주소로 보내는 SSRF 우회. 리다이렉트마다 다시 검증한다 - 가는 곳은 같은 테스트 서버지만 이름이
    // localhost(루프백)라 실제 검증기가 막는다. 검증을 건너뛰면 두 번째 요청이 나가 이유가 url-rejected가 아니게 된다
    // (localhost가 127.0.0.1로 풀리는 기계에서는 이 서버에 닿아 사본까지 저장된다)
    @Test
    fun `내부 주소로 가는 리다이렉트는 따라가지 않는다`() {
        val ogImage = serve(contentType = "image/png", body = png(1920, 1080), redirectToLocalhost = true)

        assertEquals(OgThumbnailResult.NotStored("url-rejected"), service().createThumbnail(postId, ogImage))
        assertEquals(1, requests.get())
        assertEquals(emptyList<FakeThumbnailStorage.Put>(), storage.puts)
    }

    @Test
    fun `읽지 못하는 형식이면 사본을 만들지 않는다`() {
        val ogImage = serve(contentType = "image/webp", body = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8 ".toByteArray() + ByteArray(18))

        assertEquals(OgThumbnailResult.NotStored("unsupported-format"), service().createThumbnail(postId, ogImage))
        verifyNoInteractions(postRepository)
    }

    // 로컬·테스트 기본값(app.og-thumbnail.storage=skip). 남의 사이트에 요청을 보내지도 않는다
    @Test
    fun `저장소가 꺼져 있으면 원본을 받지 않는다`() {
        val ogImage = serve(contentType = "image/png", body = png(10, 10))

        val result = service(FakeThumbnailStorage(enabled = false)).createThumbnail(postId, ogImage)

        assertEquals(OgThumbnailResult.NotStored("storage-off"), result)
        assertEquals(0, requests.get())
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `원본 주소가 바뀌면 커밋 뒤 옛 사본을 지우고 새 사본 잡을 맡긴다`() {
        val old = "https://old.example/og.png"
        val new = "https://new.example/og.png"

        service().handleOgImageChanged(PostOgImageChangedEvent(postId, previousOgImage = old, ogImage = new))

        val hash = sha256Hex(old).take(12)
        assertEquals(listOf("$postId-$hash.jpg", "$postId-$hash.png"), storage.deletes)
        verify(jobPublisher).publish(OgThumbnailJobHandler.JOB_NAME, OgThumbnailJobEvent(postId, new), "postId: $postId")
    }

    @Test
    fun `글을 지우면 옛 사본만 지우고 잡을 맡기지 않는다`() {
        val old = "https://old.example/og.png"

        service().handleOgImageChanged(PostOgImageChangedEvent(postId, previousOgImage = old, ogImage = null))

        assertEquals(2, storage.deletes.size)
        verifyNoInteractions(jobPublisher)
    }

    @Test
    fun `새 글은 지울 사본 없이 잡만 맡긴다`() {
        val new = "https://new.example/og.png"

        service().handleOgImageChanged(PostOgImageChangedEvent(postId, previousOgImage = null, ogImage = new))

        assertEquals(emptyList<String>(), storage.deletes)
        verify(jobPublisher).publish(OgThumbnailJobHandler.JOB_NAME, OgThumbnailJobEvent(postId, new), "postId: $postId")
    }

    // 지우기는 본 요청(삭제·수정)을 막지 않는다 - SupabaseStorageService.deleteObjectsByPublicUrls 선례
    @Test
    fun `옛 사본 지우기가 실패해도 예외를 내지 않고 새 사본 잡을 맡긴다`() {
        val failing = FakeThumbnailStorage(failDelete = true)
        val new = "https://new.example/og.png"

        assertDoesNotThrow {
            service(failing).handleOgImageChanged(PostOgImageChangedEvent(postId, previousOgImage = "https://old.example/og.png", ogImage = new))
        }

        verify(jobPublisher).publish(OgThumbnailJobHandler.JOB_NAME, OgThumbnailJobEvent(postId, new), "postId: $postId")
    }

    private fun service(storage: ThumbnailStorage = this.storage) = OgThumbnailService(postRepository, extractor, storage, jobPublisher)

    // /og.png가 원본이다. redirectToLocalhost면 /og.png는 같은 서버의 localhost 주소(/inner.png)로 보내고 그쪽이 원본을 준다
    private fun serve(contentType: String, body: ByteArray, status: Int = 200, redirectToLocalhost: Boolean = false): String {
        val started = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        started.createContext("/") { exchange: HttpExchange ->
            requests.incrementAndGet()

            if (redirectToLocalhost && exchange.requestURI.path == "/og.png") {
                exchange.responseHeaders.add("Location", "http://localhost:${started.address.port}/inner.png")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
                return@createContext
            }

            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        started.start()
        server = started
        return "http://127.0.0.1:${started.address.port}/og.png"
    }

    private fun png(width: Int, height: Int): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(20, 160, 90)
        graphics.fillRect(0, 0, width, height)
        graphics.dispose()
        return pngOf(image)
    }

    private fun pngOf(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    // 고정 시드 잡음. 단색 그림과 달리 압축이 잘 되지 않아 품질에 따라 크기가 크게 달라진다
    private fun noisy(width: Int, height: Int): BufferedImage {
        val random = Random(42)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, random.nextInt(0x1000000))
            }
        }

        return image
    }

    private fun jpeg(image: BufferedImage, quality: Float): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val param =
            writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = quality
            }
        val bytes = ByteArrayOutputStream()

        try {
            MemoryCacheImageOutputStream(bytes).use { output ->
                writer.output = output
                writer.write(null, IIOImage(image, null, null), param)
            }
        } finally {
            writer.dispose()
        }

        return bytes.toByteArray()
    }

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    class FakeThumbnailStorage(override val enabled: Boolean = true, private val failDelete: Boolean = false) : ThumbnailStorage {
        data class Put(val name: String, val bytes: ByteArray, val contentType: String, val cacheControl: String)

        val puts = mutableListOf<Put>()
        val deletes = mutableListOf<String>()

        override fun put(name: String, bytes: ByteArray, contentType: String, cacheControl: String): String {
            puts += Put(name, bytes, contentType, cacheControl)
            return "https://cdn.test/og-thumbnails/$name"
        }

        override fun delete(name: String) {
            if (failDelete) {
                throw IllegalStateException("S3 down")
            }

            deletes += name
        }
    }
}
