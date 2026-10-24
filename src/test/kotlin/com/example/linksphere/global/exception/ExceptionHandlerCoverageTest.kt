package com.example.linksphere.global.exception

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AssignableTypeFilter
import org.springframework.web.bind.annotation.ExceptionHandler

// global/exception/의 예외 클래스가 모두 GlobalExceptionHandler에 등록돼 있는지 강제한다(BE CLAUDE.md Critical
// Rules "GlobalExceptionHandler 수정 없이 새 예외 클래스 추가 금지"). 등록이 빠지면 handleException(500)으로
// 떨어져 의도한 status·code가 나가지 않는다. SwaggerDocsCoverageTest처럼 Spring 컨텍스트 없이 클래스패스만 본다.
class ExceptionHandlerCoverageTest {

    private val exceptionPackage = "com.example.linksphere.global.exception"

    // 스캐너가 아무것도 못 찾아 조용히 통과하는 것을 막는 하한선 - 정확한 개수가 아니라 "스캔이 동작했다"는
    // 것만 보증한다(2026-10 기준 17개).
    private val minimumExceptionCount = 10

    @Test
    fun `global exception 패키지의 모든 예외에 @ExceptionHandler가 있다`() {
        val exceptions = findExceptionClasses()

        assertTrue(
            exceptions.size >= minimumExceptionCount,
            "예외 클래스를 최소 $minimumExceptionCount 개 찾아야 하는데 ${exceptions.size}개만 찾았다. " +
                "패키지가 바뀌었으면 exceptionPackage($exceptionPackage)를 확인한다.",
        )

        val handled = handledExceptionTypes()
        val missing = exceptions.filterNot { it in handled }.map { it.simpleName }.sorted()

        assertTrue(
            missing.isEmpty(),
            buildString {
                appendLine("GlobalExceptionHandler에 등록되지 않은 예외가 ${missing.size}개 있다:")
                missing.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("@ExceptionHandler 메서드를 추가하고 ErrorResponse(status, code, message)를 돌려준다.")
                appendLine("code 표와 선택 기준은 docs/BE-ARCHITECTURE.md §4.")
            },
        )
    }

    private fun findExceptionClasses(): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AssignableTypeFilter(Throwable::class.java))
        return scanner.findCandidateComponents(exceptionPackage)
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it) }
    }

    // value를 비워 두면 Spring은 메서드 파라미터 타입으로 판단하지만, 이 레포 핸들러는 모두 value를 명시한다.
    private fun handledExceptionTypes(): Set<Class<*>> = GlobalExceptionHandler::class.java.declaredMethods
        .mapNotNull { it.getAnnotation(ExceptionHandler::class.java) }
        .flatMap { it.value.map { type -> type.java } }
        .toSet()
}
