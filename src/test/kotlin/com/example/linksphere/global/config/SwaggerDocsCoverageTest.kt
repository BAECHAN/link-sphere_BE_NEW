package com.example.linksphere.global.config

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method

// 모든 @RestController 엔드포인트가 Swagger 문서를 갖고 있는지 강제한다.
// Spring 컨텍스트를 띄우지 않고 클래스패스만 스캔하므로 DB·Firebase 설정 없이 수십 ms 안에 끝난다.
// 새 엔드포인트를 추가하고 @Operation 을 빠뜨리면 ktlintCheck test 가 깨진다.
class SwaggerDocsCoverageTest {

    private val basePackage = "com.example.linksphere"

    // 스캐너가 아무것도 못 찾아 테스트가 조용히 통과하는 것을 막는 하한선.
    // 정확한 개수가 아니라 "스캔이 동작했다"는 것만 보증한다 (2026-09 기준 8개).
    private val minimumControllerCount = 8

    @Test
    fun `모든 컨트롤러에 @Tag 가, 모든 엔드포인트에 @Operation summary 가 있다`() {
        val controllers = findRestControllers()

        assertTrue(
            controllers.size >= minimumControllerCount,
            "@RestController 를 최소 $minimumControllerCount 개 찾아야 하는데 ${controllers.size}개만 찾았다. " +
                "패키지 구조가 바뀌었으면 basePackage($basePackage)를 확인한다.",
        )

        val violations = mutableListOf<String>()

        controllers.sortedBy { it.name }.forEach { controller ->
            if (AnnotatedElementUtils.findMergedAnnotation(controller, Tag::class.java) == null) {
                violations += "${controller.simpleName}: 클래스에 @Tag(name = \"...\") 가 없다"
            }

            controller.declaredMethods
                .filter { it.isRequestMapped() }
                .sortedBy { it.name }
                .forEach { method ->
                    val operation = AnnotatedElementUtils.findMergedAnnotation(method, Operation::class.java)
                    when {
                        operation == null ->
                            violations += "${controller.simpleName}.${method.name}(): @Operation(summary = \"...\") 가 없다"

                        operation.summary.isBlank() ->
                            violations += "${controller.simpleName}.${method.name}(): @Operation.summary 가 비어 있다"
                    }
                }
        }

        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("Swagger 문서가 빠진 곳이 ${violations.size}건 있다:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("작성 규칙과 예시는 domain/auth/AuthController.kt 를 그대로 따른다.")
                appendLine("permitAll 엔드포인트라면 @SecurityRequirements 도 함께 붙인다(SecurityConfig 목록 확인).")
            },
        )
    }

    private fun findRestControllers(): List<Class<*>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        return scanner.findCandidateComponents(basePackage)
            .mapNotNull { it.beanClassName }
            .map { Class.forName(it) }
    }

    // @GetMapping·@PostMapping 등은 모두 @RequestMapping 을 메타 애노테이션으로 갖는다
    private fun Method.isRequestMapped(): Boolean = !isSynthetic && AnnotatedElementUtils.hasAnnotation(this, RequestMapping::class.java)
}
