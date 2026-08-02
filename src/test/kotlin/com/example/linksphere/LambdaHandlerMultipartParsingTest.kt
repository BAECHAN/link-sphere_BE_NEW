package com.example.linksphere

import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MultipartRequestParser.parse()를 직접 검증한다.
 *
 * MockHttpServletRequest.getParts()는 raw multipart body를 파싱하지 않고 사전 등록된
 * Part만 반환하므로(spring-test에 파싱 로직 자체가 없음), LambdaHandler는
 * MultipartRequestParser로 raw 바이트를 직접 해석한다. 이 회귀 테스트가 없으면 같은
 * 버그가 다시 들어와도 아무 테스트도 실패하지 않는다.
 *
 * MultipartRequestParser는 spring-test/Spring Boot에 결합돼 있지 않으므로(순수 파싱
 * 로직만 있음), LambdaHandler의 companion object init(전체 Spring Boot 부팅)을 거치지
 * 않고 바로 테스트할 수 있다.
 */
class LambdaHandlerMultipartParsingTest {

    private val boundary = "----WebKitFormBoundaryTest1234"
    private val contentType = "multipart/form-data; boundary=$boundary"

    private fun buildMultipartBody(vararg parts: Part): ByteArray {
        val out = ByteArrayOutputStream()
        fun write(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
        parts.forEach { part ->
            write("--$boundary\r\n")
            val dispositionSuffix = part.filename?.let { "; filename=\"$it\"" } ?: ""
            write("Content-Disposition: form-data; name=\"${part.name}\"$dispositionSuffix\r\n")
            part.contentType?.let { write("Content-Type: $it\r\n") }
            write("\r\n")
            out.write(part.bytes)
            write("\r\n")
        }
        write("--$boundary--\r\n")
        return out.toByteArray()
    }

    private data class Part(
        val name: String,
        val filename: String? = null,
        val contentType: String? = null,
        val bytes: ByteArray,
    ) {
        constructor(name: String, value: String) : this(name, bytes = value.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `text-only body maps to a single field`() {
        val bytes = buildMultipartBody(Part("content", "https://m.blog.naver.com/csa6109/224351979119?view=img_13"))

        val result = MultipartRequestParser.parse(bytes, contentType)

        assertEquals(listOf("https://m.blog.naver.com/csa6109/224351979119?view=img_13"), result.fields["content"])
        assertTrue(result.files.isEmpty())
    }

    @Test
    fun `body with an image file maps to both field and file`() {
        val fakeImageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)
        val bytes =
            buildMultipartBody(
                Part("content", "스크린샷 첨부"),
                Part("images", filename = "shot.png", contentType = "image/png", bytes = fakeImageBytes),
            )

        val result = MultipartRequestParser.parse(bytes, contentType)

        assertEquals(listOf("스크린샷 첨부"), result.fields["content"])
        assertEquals(1, result.files.size)
        val file = result.files.single()
        assertEquals("images", file.fieldName)
        assertEquals("shot.png", file.filename)
        assertEquals("image/png", file.contentType)
        assertTrue(file.bytes.contentEquals(fakeImageBytes))
    }

    @Test
    fun `Korean filename is decoded correctly`() {
        val bytes =
            buildMultipartBody(
                Part("images", filename = "스크린샷.png", contentType = "image/png", bytes = byteArrayOf(1, 2, 3)),
            )

        val result = MultipartRequestParser.parse(bytes, contentType)

        assertEquals("스크린샷.png", result.files.single().filename)
    }

    @Test
    fun `part with a filename is classified as a file, not a field`() {
        val bytes = buildMultipartBody(Part("images", filename = "shot.png", contentType = "image/png", bytes = byteArrayOf(1)))

        val result = MultipartRequestParser.parse(bytes, contentType)

        assertEquals(1, result.files.size)
        assertNull(result.fields["images"])
    }

    @Test
    fun `part without a filename is classified as a field, not a file`() {
        val bytes = buildMultipartBody(Part("content", "hello"))

        val result = MultipartRequestParser.parse(bytes, contentType)

        assertTrue(result.files.isEmpty())
        assertEquals(listOf("hello"), result.fields["content"])
    }

    @Test
    fun `repeated field name accumulates all values`() {
        val bytes = buildMultipartBody(Part("tag", "kotlin"), Part("tag", "spring"))

        val result = MultipartRequestParser.parse(bytes, contentType)

        assertEquals(listOf("kotlin", "spring"), result.fields["tag"])
    }
}
