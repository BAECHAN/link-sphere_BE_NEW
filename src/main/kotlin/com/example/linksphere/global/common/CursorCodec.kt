package com.example.linksphere.global.common

import com.example.linksphere.global.exception.InvalidInputException
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

// cursor에 문자열로 담은 정렬 키를 비교할 수 있는 값으로 되돌린다
enum class CursorKeyType(private val parser: (String) -> Comparable<*>) {
    TIME(LocalDateTime::parse),
    ID(UUID::fromString),
    NUMBER(String::toDouble),

    // NUL은 Postgres 문자열 인자로 보낼 수 없어 500이 된다 - 정상 제목에는 없으니 조작된 cursor로 본다
    TEXT({ raw -> if ('\u0000' in raw) throw InvalidInputException("Invalid cursor") else raw }),
    ;

    fun parse(raw: String): Comparable<*> = parser(raw)
}

data class CursorState(
    val keys: List<Comparable<*>>,
    // 첫 쪽이 의미 검색이었는가. 검색이 아니면 null
    val semantic: Boolean? = null,
    // 첫 쪽에서 자판 보정으로 재검색했는가. 아니면 null
    val corrected: Boolean? = null,
)

/**
 * 목록 cursor를 Base64url JSON으로 만들고 읽는다(docs/PAGINATION.md).
 *
 * 서명하지 않는다 - 공개 범위 조건은 요청마다 쿼리가 적용하므로 조작해도 볼 수 없는 글은 나오지 않고, 위치만 달라진다.
 * 형식을 바꾸면 VERSION을 올리되 옛 값도 계속 읽어야 한다. 열려 있는 탭이 옛 cursor를 들고 있고, 400이면 재시도해도 같은 값을 보낸다.
 */
object CursorCodec {
    private const val VERSION = 1
    private val mapper = jacksonObjectMapper()

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private data class Payload(
        val v: Int,
        val sort: String,
        val keys: List<String>,
        val semantic: Boolean? = null,
        val corrected: Boolean? = null,
    )

    fun encode(sort: String, keys: List<Comparable<*>>, semantic: Boolean? = null, corrected: Boolean? = null): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(mapper.writeValueAsBytes(Payload(VERSION, sort, keys.map { it.toString() }, semantic, corrected)))

    /** 지금 요청의 정렬과 다른 cursor는 키를 해석할 수 없어 잘못된 입력으로 돌려보낸다. */
    fun decode(cursor: String, sort: String, keyTypes: List<CursorKeyType>): CursorState {
        try {
            val payload = mapper.readValue<Payload>(Base64.getUrlDecoder().decode(cursor))
            if (payload.v != VERSION || payload.sort != sort || payload.keys.size != keyTypes.size) {
                throw InvalidInputException("Invalid cursor")
            }

            return CursorState(keyTypes.zip(payload.keys) { type, raw -> type.parse(raw) }, payload.semantic, payload.corrected)
        } catch (e: IllegalArgumentException) {
            throw InvalidInputException("Invalid cursor")
        } catch (e: JacksonException) {
            throw InvalidInputException("Invalid cursor")
        } catch (e: DateTimeParseException) {
            throw InvalidInputException("Invalid cursor")
        }
    }
}
