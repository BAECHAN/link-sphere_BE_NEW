package com.example.linksphere

/**
 * Function URL 이벤트의 rawQueryString을 serverless-java-container가 Tomcat과 같은 파라미터로 읽도록 고친다.
 *
 * 라이브러리는 쌍을 `split("=")`로 나눈다(core 2.1.7 `AwsHttpApiV2ProxyHttpServletRequest.parseRawQueryString`). 그래서
 * Tomcat(첫 `=`만 나누고 이름이 빈 쌍은 버린다)과 세 곳이 다르다.
 * - `search=a=b`: 조각이 3개라 값이 `""`가 된다(검색어가 사라진다) → 값 안의 `=`를 `%3D`로 바꾼다.
 * - `search`: 값이 `null`이 되고 경고 로그가 남는다 → `search=`로 바꿔 Tomcat처럼 빈 값으로 읽게 한다.
 * - `=`: `"=".split("=")`가 빈 배열이라 `ArrayIndexOutOfBoundsException`이 나고, 잡는 예외가
 *   `UnsupportedEncodingException`뿐이라 함수 실패가 된다 → 이름이 빈 쌍과 빈 조각(`a=1&&b=2`)을 버린다.
 *
 * `+`는 라이브러리가 공백으로 읽으므로(URLDecoder) 그대로 둔다. FE의 `URLSearchParams`는 `=`를 `%3D`로 보내므로 위 경우는
 * 손으로 친 주소에서만 생긴다. 이름이 빈 쌍은 운영에서 Function URL이 Lambda를 부르기 전에 막는다(`?=` 400, `?size=5&=` 403,
 * 2026-11-10 측정). 그래도 이 함수가 요청 하나로 죽지 않게 버린다. 계획과 측정은 docs/plans/2026-11-10-serving-path-switch.md.
 *
 * LambdaHandler의 companion object init(Spring Boot 전체 부팅)을 거치지 않고 단위 테스트하려고 따로 둔다.
 */
internal object FunctionUrlQuery {

    fun normalize(rawQueryString: String): String = rawQueryString
        .split("&")
        .filter { it.isNotEmpty() && !it.startsWith("=") }
        .joinToString("&") { pair ->
            val index = pair.indexOf('=')
            if (index < 0) "$pair=" else pair.substring(0, index + 1) + pair.substring(index + 1).replace("=", "%3D")
        }
}
