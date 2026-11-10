package com.example.linksphere

import com.amazonaws.serverless.proxy.model.AwsProxyResponse
import org.springframework.http.HttpHeaders

/**
 * serverless-java-container 응답을 Lambda Function URL 응답 페이로드(statusCode·headers·cookies·body·isBase64Encoded)로 바꾼다.
 *
 * - `Set-Cookie`는 `headers`가 아니라 `cookies` 배열로 보낸다. AWS 문서가 _"함수에서 쿠키를 돌려주려면 set-cookie 헤더를
 *   직접 넣지 말고 응답 페이로드 객체의 cookies에 넣는다"_ 고 쓴다(번역,
 *   https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html "Cookies"). 쿠키는 콤마로 합치면 안 된다 —
 *   `Expires`(`Thu, 01 Jan 1970 …`)에 콤마가 들어 있다. 라이브러리 응답에는 `cookies` 필드가 없어 여기서 만든다.
 * - 나머지 헤더는 값이 여럿이면 `, `로 합친다. 예전에는 첫 값만 실어 CORS가 붙이는 `Vary`의 세 값 중 `Origin`만 나갔다
 *   (2026-10-26 운영 응답으로 확인, spring-framework 6.2 `DefaultCorsProcessor`).
 * - 라이브러리가 바이너리 Content-Type 본문을 base64로 바꿨으면 `isBase64Encoded`로 알린다.
 *
 * LambdaHandler의 companion object init(Spring Boot 전체 부팅)을 거치지 않고 단위 테스트하려고 따로 둔다.
 */
internal object FunctionUrlResponse {

    fun from(response: AwsProxyResponse): Map<String, Any> {
        val multiValueHeaders = response.multiValueHeaders?.entries.orEmpty()
        val cookies =
            multiValueHeaders
                .filter { it.key.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
                .flatMap { it.value }
        val headers =
            multiValueHeaders
                .filterNot { it.key.equals(HttpHeaders.SET_COOKIE, ignoreCase = true) }
                .associate { it.key to it.value.joinToString(", ") }

        return buildMap {
            put("statusCode", response.statusCode)
            put("headers", headers)
            if (cookies.isNotEmpty()) {
                put("cookies", cookies)
            }
            put("body", response.body ?: "")
            put("isBase64Encoded", response.isBase64Encoded)
        }
    }
}
