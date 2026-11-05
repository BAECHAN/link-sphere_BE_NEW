package com.example.linksphere.global.common

import com.example.linksphere.global.exception.InvalidInputException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

class CursorCodecTest {

    private val time = LocalDateTime.of(2026, 11, 5, 12, 34, 56, 123_456_000)
    private val id = UUID.randomUUID()
    private val types = listOf(CursorKeyType.NUMBER, CursorKeyType.TIME, CursorKeyType.ID)

    private fun rawCursor(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test
    fun `키와 상태를 값 그대로 왕복한다`() {
        val keys = listOf<Comparable<*>>(196067L, time, id)

        val state = CursorCodec.decode(CursorCodec.encode("RELEVANCE", keys, semantic = true, corrected = true), "RELEVANCE", types)

        assertEquals(keys, state.keys)
        assertEquals(true, state.semantic)
        assertEquals(true, state.corrected)
    }

    // LocalDateTime.toString은 초가 0이면 초를 생략한다 - 그 형태도 parse가 읽어야 한다
    @Test
    fun `초가 0인 시각과 열람 없음 값도 왕복한다`() {
        val never = LocalDateTime.of(1970, 1, 1, 0, 0)

        val state = CursorCodec.decode(CursorCodec.encode("VIEWED", listOf(never, id)), "VIEWED", listOf(CursorKeyType.TIME, CursorKeyType.ID))

        assertEquals(listOf<Comparable<*>>(never, id), state.keys)
        assertNull(state.semantic)
        assertNull(state.corrected)
    }

    @Test
    fun `제목 키는 한글과 기호를 그대로 왕복하고 cursor는 URL에 안전한 글자만 쓴다`() {
        val title = "가나다 + /? & = \"따옴표\" 🙂"

        val cursor = CursorCodec.encode("TITLE", listOf(title, time, id))

        assertFalse(cursor.any { it in "+/=" })
        assertEquals(title, CursorCodec.decode(cursor, "TITLE", listOf(CursorKeyType.TEXT, CursorKeyType.TIME, CursorKeyType.ID)).keys[0])
    }

    @Test
    fun `정렬이 다른 cursor는 잘못된 입력이다`() {
        val cursor = CursorCodec.encode("LATEST", listOf(time, id))

        assertThrows(InvalidInputException::class.java) { CursorCodec.decode(cursor, "OLDEST", listOf(CursorKeyType.TIME, CursorKeyType.ID)) }
    }

    @Test
    fun `해석할 수 없는 cursor는 모두 잘못된 입력이다`() {
        val invalid =
            listOf(
                "!!! not base64 !!!",
                rawCursor("not json"),
                rawCursor("""{"v":1,"sort":"RELEVANCE"}"""),
                // 다른 버전, 키 개수 불일치
                rawCursor("""{"v":99,"sort":"RELEVANCE","keys":["10000","2026-11-05T12:00:00","$id"]}"""),
                rawCursor("""{"v":1,"sort":"RELEVANCE","keys":["10000"]}"""),
                // 키 타입 불일치
                rawCursor("""{"v":1,"sort":"RELEVANCE","keys":["abc","2026-11-05T12:00:00","$id"]}"""),
                rawCursor("""{"v":1,"sort":"RELEVANCE","keys":["84.5","2026-11-05T12:00:00","$id"]}"""),
                rawCursor("""{"v":1,"sort":"RELEVANCE","keys":["10000","어제","$id"]}"""),
                rawCursor("""{"v":1,"sort":"RELEVANCE","keys":["10000","2026-11-05T12:00:00","not-a-uuid"]}"""),
            )

        invalid.forEach { cursor ->
            assertThrows(InvalidInputException::class.java, { CursorCodec.decode(cursor, "RELEVANCE", types) }, cursor)
        }
    }

    // Postgres 문자열 인자는 NUL을 받지 못해 그대로 보내면 500이 된다
    @Test
    fun `제목 키에 NUL이 있으면 잘못된 입력이다`() {
        val cursor = rawCursor("""{"v":1,"sort":"TITLE","keys":["a\u0000b","2026-11-05T12:00:00","$id"]}""")

        assertThrows(InvalidInputException::class.java) {
            CursorCodec.decode(cursor, "TITLE", listOf(CursorKeyType.TEXT, CursorKeyType.TIME, CursorKeyType.ID))
        }
    }
}
