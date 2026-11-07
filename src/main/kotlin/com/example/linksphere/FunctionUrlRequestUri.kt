package com.example.linksphere

import java.net.URI

/**
 * Function URL 이벤트의 경로와 쿼리를 MockMvc에 넘길 URI로 만든다.
 *
 * `%ZZ`·`|`처럼 URI 문법에 맞지 않는 입력은 `URI.create`가 `IllegalArgumentException`을 던진다. 이 예외를 잡지 않으면
 * 함수 실패로 끝나 502와 `Errors` 알람 조건이 된다(2026-11-07 RIE 비교, `scripts/serving-path-experiment/results/2026-11-07.md`).
 * 그래서 null을 돌려주고 호출한 쪽이 400으로 끝낸다.
 *
 * LambdaHandler의 companion object init(Spring Boot 전체 부팅)을 거치지 않고 단위 테스트하려고 따로 둔다.
 */
internal object FunctionUrlRequestUri {

    fun parseOrNull(path: String, query: String?): URI? = try {
        URI.create(if (query != null) "$path?$query" else path)
    } catch (_: IllegalArgumentException) {
        null
    }
}
