package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import javax.imageio.ImageIO

// 피드 카드용 사본의 크기·형식 규칙(docs/plans/2026-11-25-og-thumbnail.md "크기 규칙"·"형식"·"받기 상한")을 네트워크 없이 본다
class OgThumbnailResizerTest {

    @Test
    fun `3200×2160 PNG는 960×648 JPEG로 줄인다`() {
        val result = OgThumbnailResizer.resize(encode(opaque(3200, 2160), "png")) as OgThumbnailResizer.Resized

        assertEquals("jpg", result.extension)
        assertEquals("image/jpeg", result.contentType)
        assertEquals(listOf(0xFF, 0xD8), result.bytes.take(2).map { it.toInt() and 0xFF })
        assertEquals(960 to 648, decodedSize(result.bytes))
    }

    // 카드가 16:9 가운데를 잘라 그리므로 960×540을 덮어야 한다 - 세로가 긴 그림은 가로 960에 맞춘다
    @Test
    fun `세로가 긴 원본도 960×540을 덮는 최소 크기로 줄인다`() {
        val result = OgThumbnailResizer.resize(encode(opaque(1000, 2000), "png")) as OgThumbnailResizer.Resized

        assertEquals(960 to 1920, decodedSize(result.bytes))
    }

    @Test
    fun `원본이 960×540보다 작으면 키우지 않고 다시 인코딩만 한다`() {
        val result = OgThumbnailResizer.resize(encode(opaque(800, 400), "png")) as OgThumbnailResizer.Resized

        assertEquals("jpg", result.extension)
        assertEquals(800 to 400, decodedSize(result.bytes))
    }

    // 투명 PNG를 JPEG로 바꾸면 배경색이 생겨 다크 모드 화면이 바뀐다
    @Test
    fun `투명한 픽셀이 있으면 PNG로 줄인다`() {
        val image = BufferedImage(1920, 1080, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(30, 120, 200, 255)
        graphics.fillRect(0, 0, 960, 1080)
        graphics.dispose()

        val result = OgThumbnailResizer.resize(encode(image, "png")) as OgThumbnailResizer.Resized

        assertEquals("png", result.extension)
        assertEquals("image/png", result.contentType)
        val decoded = ImageIO.read(ByteArrayInputStream(result.bytes))
        assertEquals(960 to 540, decoded.width to decoded.height)
        assertEquals(0, decoded.getRGB(900, 270) ushr 24)
        assertEquals(255, decoded.getRGB(100, 270) ushr 24)
    }

    // 디자인 도구가 내보낸 og 이미지는 투명 채널만 있고 모두 불투명한 경우가 많다 - 채널이 아니라 픽셀을 본다
    @Test
    fun `투명 채널이 있어도 모든 픽셀이 불투명하면 JPEG로 줄인다`() {
        val image = BufferedImage(1200, 630, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(200, 40, 40, 255)
        graphics.fillRect(0, 0, 1200, 630)
        graphics.dispose()

        val result = OgThumbnailResizer.resize(encode(image, "png")) as OgThumbnailResizer.Resized

        assertEquals("jpg", result.extension)
        assertEquals(1029 to 540, decodedSize(result.bytes))
    }

    // 압축 폭탄: 몇십 바이트짜리 파일이 머리에는 10000×6000이라고 적혀 있다. 픽셀을 풀었다면 본문이 없어 읽기 실패가 났을 것이다
    @Test
    fun `머리에 적힌 픽셀 수가 5,000만을 넘으면 픽셀을 풀기 전에 거절한다`() {
        val result = OgThumbnailResizer.resize(pngHeaderOnly(width = 10_000, height = 6_000))

        assertEquals(OgThumbnailResizer.Rejected("too-many-pixels"), result)
    }

    @Test
    fun `JDK가 읽지 못하는 형식이면 사본을 만들지 않는다`() {
        val webp = "RIFF".toByteArray() + byteArrayOf(26, 0, 0, 0) + "WEBPVP8 ".toByteArray() + ByteArray(18)

        assertEquals(OgThumbnailResizer.Rejected("unsupported-format"), OgThumbnailResizer.resize(webp))
        assertEquals(OgThumbnailResizer.Rejected("unsupported-format"), OgThumbnailResizer.resize("<html></html>".toByteArray()))
    }

    @Test
    fun `머리는 읽혀도 본문이 깨졌으면 사본을 만들지 않는다`() {
        val result = OgThumbnailResizer.resize(pngHeaderOnly(width = 100, height = 100))

        assertEquals(OgThumbnailResizer.Rejected("decode-failed"), result)
    }

    @Test
    fun `GIF도 첫 장면을 JPEG로 줄인다`() {
        val result = OgThumbnailResizer.resize(encode(opaque(1920, 1080, BufferedImage.TYPE_BYTE_INDEXED), "gif")) as OgThumbnailResizer.Resized

        assertTrue(result.extension == "jpg", "extension=${result.extension}")
        assertEquals(960 to 540, decodedSize(result.bytes))
    }

    private fun opaque(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB): BufferedImage {
        val image = BufferedImage(width, height, type)
        val graphics = image.createGraphics()
        graphics.color = Color(20, 160, 90)
        graphics.fillRect(0, 0, width, height)
        graphics.color = Color.WHITE
        graphics.fillRect(width / 4, height / 4, width / 2, height / 2)
        graphics.dispose()
        return image
    }

    private fun encode(image: BufferedImage, format: String): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    private fun decodedSize(bytes: ByteArray): Pair<Int, Int> {
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        return decoded.width to decoded.height
    }

    // PNG 서명 + IHDR(가로·세로, 8비트 RGB) + IEND. 픽셀 본문(IDAT)이 없다
    private fun pngHeaderOnly(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream().also { body ->
            DataOutputStream(body).apply {
                writeInt(width)
                writeInt(height)
                write(byteArrayOf(8, 2, 0, 0, 0))
            }
        }.toByteArray()
        writeChunk(data, "IHDR", ihdr)
        writeChunk(data, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(data: DataOutputStream, type: String, body: ByteArray) {
        val typeBytes = type.toByteArray()
        data.writeInt(body.size)
        data.write(typeBytes)
        data.write(body)
        data.writeInt(CRC32().apply { update(typeBytes + body) }.value.toInt())
    }
}
