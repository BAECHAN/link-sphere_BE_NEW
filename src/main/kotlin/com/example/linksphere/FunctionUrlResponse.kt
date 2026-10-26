package com.example.linksphere

import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletResponse

/**
 * MockMvc 응답을 Lambda Function URL 응답 페이로드(statusCode·headers·cookies·body)로 바꾼다.
 *
 * - `Set-Cookie`는 `headers`가 아니라 `cookies` 배열로 보낸다. AWS 문서가 _"함수에서 쿠키를 돌려주려면 set-cookie 헤더를
 *   직접 넣지 말고 응답 페이로드 객체의 cookies에 넣는다"_ 고 쓴다(번역,
 *   https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html "Cookies"). 쿠키는 콤마로 합치면 안 된다 —
 *   `Expires`(`Thu, 1 Jan 1970 …`)에 콤마가 들어 있다.
 * - 나머지 헤더는 값이 여럿이면 `, `로 합친다. 예전에는 `getHeader`(첫 값)만 실어 CORS가 붙이는 `Vary`의 세 값 중
 *   `Origin`만 나갔다(2026-10-26 운영 응답으로 확인, spring-framework 6.2 `DefaultCorsProcessor`).
 *
 * LambdaHandler의 companion object init(Spring Boot 전체 부팅)을 거치지 않고 단위 테스트하려고 따로 둔다.
 */
internal object FunctionUrlResponse {

    fun from(response: MockHttpServletResponse): Map<String, Any> {
        val cookies = response.getHeaders(HttpHeaders.SET_COOKIE)
        val headers =
            response.headerNames
                .filterNot { it.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
                .associateWith { response.getHeaders(it).joinToString(", ") }

        return buildMap {
            put("statusCode", response.status)
            put("headers", headers)
            if (cookies.isNotEmpty()) {
                put("cookies", cookies)
            }
            put("body", response.contentAsString)
        }
    }
}
