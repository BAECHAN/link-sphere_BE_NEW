package com.example.linksphere.global.exception

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.ConversionNotSupportedException
import org.springframework.beans.TypeMismatchException
import org.springframework.http.converter.HttpMessageNotWritableException
import org.springframework.validation.method.MethodValidationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.context.request.WebRequest
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler

/**
 * Spring MVC 표준 예외가 GlobalExceptionHandler의 Exception 안전망(500 INTERNAL_SERVER_ERROR)으로 떨어지지 않는지 본다.
 * 2026-10-26 전에는 405(지원하지 않는 메서드)·필수 파라미터 누락이 운영에서 500으로 나갔다.
 *
 * 대상은 손으로 적지 않고 설치된 Spring의 ResponseEntityExceptionHandler.handleException에 붙은 @ExceptionHandler에서
 * 읽는다 - Spring 문서가 "모든 Spring MVC 예외"를 처리한다고 소개하는 목록이라(docs/BE-ARCHITECTURE.md §11), Spring을
 * 올려 예외가 늘면 이 테스트가 먼저 알려 준다. ExceptionHandlerCoverageTest는 우리 예외(global.exception)를, 이 테스트는
 * Spring 예외를 본다.
 */
class SpringMvcExceptionCoverageTest {

    // 안전망에 맡겨도 되는 것 - Spring 기본 처리도 500이거나 응답을 쓰지 않는 것, 그리고 이 앱에서는 하위형으로만 오는 것.
    // 줄 번호는 spring-webmvc 6.2.14 ResponseEntityExceptionHandler.java 기준이다.
    private val allowedFallbacks: Map<Class<*>, String> =
        mapOf(
            ConversionNotSupportedException::class.java to "서버 쪽에 변환기가 없음 - Spring도 500(:189)",
            HttpMessageNotWritableException::class.java to "응답 직렬화 실패 - Spring도 500(:198)",
            MethodValidationException::class.java to "반환값 검증 실패 - Spring도 500(:201)",
            AsyncRequestNotUsableException::class.java to "클라이언트가 끊긴 뒤의 쓰기 - Spring은 응답을 쓰지 않는다(:584-588)",
            TypeMismatchException::class.java to
                "@RequestParam·@PathVariable 변환 실패는 하위형 MethodArgumentTypeMismatchException으로 감싸져 " +
                "(spring-web 6.2.14 AbstractNamedValueMethodArgumentResolver.java:299-300) 전용 핸들러(400)가 받는다",
        )

    // 목록을 못 읽어 조용히 통과하는 것을 막는 하한선 - 정확한 개수가 아니다(spring-webmvc 6.2.14 기준 20개).
    private val minimumTypeCount = 15

    @Test
    fun `Spring MVC 표준 예외는 500 안전망으로 떨어지지 않는다`() {
        val types =
            ResponseEntityExceptionHandler::class.java
                .getMethod("handleException", Exception::class.java, WebRequest::class.java)
                .getAnnotation(ExceptionHandler::class.java)
                .value
                .map { it.java }

        assertTrue(
            types.size >= minimumTypeCount,
            "Spring MVC 표준 예외를 최소 $minimumTypeCount 개 읽어야 하는데 ${types.size}개만 읽었다.",
        )

        val staleAllowances = allowedFallbacks.keys - types.toSet()

        assertTrue(staleAllowances.isEmpty(), "허용 목록에 Spring 목록에 없는 타입이 있다(지운다): $staleAllowances")

        val resolver = ExceptionHandlerMethodResolver(GlobalExceptionHandler::class.java)
        val safetyNet = GlobalExceptionHandler::class.java.getMethod("handleException", Exception::class.java)
        val violations = types.filter { it !in allowedFallbacks && resolver.resolveMethodByExceptionType(it) == safetyNet }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("500 안전망으로 떨어지는 Spring MVC 표준 예외가 ${violations.size}개 있다:")
                violations.forEach { appendLine("  - ${it.name}") }
                appendLine()
                appendLine(
                    "ErrorResponse를 구현하면 handleSpringMvcException 대상에 넣고, 아니면 전용 핸들러를 만들거나 " +
                        "500이 맞는 이유를 allowedFallbacks에 적는다",
                )
            },
        )
    }
}
