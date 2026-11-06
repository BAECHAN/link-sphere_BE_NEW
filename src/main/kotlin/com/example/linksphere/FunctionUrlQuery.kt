package com.example.linksphere

/**
 * Function URL 이벤트의 rawQueryString을 MockMvc에 넘길 쿼리 문자열로 바꾼다.
 *
 * 서블릿 컨테이너는 쿼리의 `+`를 공백으로 읽지만(tomcat-embed-core 10.1.55 `UDecoder.convert`), MockMvc는 `%XX`만 해석해
 * `+`를 글자로 남긴다(spring-test 6.2.19 `AbstractMockHttpServletRequestBuilder.addRequestParams` → spring-core 6.2.19
 * `StringUtils.uriDecode`). FE의 `URLSearchParams`가 공백을 `+`로 보내므로, 이대로면 두 단어 검색이 한 단어로 처리된다
 * (2026-11-06 운영 측정, "Spring Boot" 1건 ↔ `%20`으로 보내면 5건). 글자 그대로의 `+`는 `%2B`로 오므로 영향이 없다.
 *
 * LambdaHandler의 companion object init(Spring Boot 전체 부팅)을 거치지 않고 단위 테스트하려고 따로 둔다.
 */
internal object FunctionUrlQuery {

    fun toMockMvcQuery(rawQueryString: String): String = rawQueryString.replace("+", "%20")
}
