package com.example.linksphere.architecture

import com.example.linksphere.global.common.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.lang.reflect.Method
import java.lang.reflect.ParameterizedType
import java.security.Principal
import kotlin.reflect.jvm.kotlinFunction

/**
 * 컨트롤러 엔드포인트의 시그니처 규칙(docs/BE-ARCHITECTURE.md §4·§5). Kotlin nullability와 제네릭 반환형을 봐야 해서
 * ArchUnit 대신 리플렉션으로 검사한다 - SwaggerDocsCoverageTest처럼 Spring 컨텍스트 없이 클래스패스만 스캔한다.
 *
 * - 사용자는 `Authentication`으로 받고 SecurityUtils의 `getUserId()`로 꺼낸다(`Principal`·`@AuthenticationPrincipal` 금지)
 * - `Authentication?`(비로그인 허용)는 `@SecurityRequirements`로 공개 엔드포인트임을 밝힌 곳에만 쓴다 - 인증 필수
 *   엔드포인트는 Security가 인증을 보장하므로 non-null이다
 * - 응답은 `ApiResponse`로 감싼다(쿠키가 필요한 auth 엔드포인트만 `ResponseEntity<ApiResponse<…>>`)
 */
class ControllerSignatureRulesTest {

    private val basePackage = "com.example.linksphere"

    // 스캔이 아무것도 못 찾아 조용히 통과하는 것을 막는 하한선 - 정확한 개수가 아니다(2026-10 기준 엔드포인트 50여 개).
    private val minimumEndpointCount = 40

    @Test
    fun `엔드포인트는 Principal이나 @AuthenticationPrincipal로 사용자를 받지 않는다`() {
        val violations =
            endpoints().flatMap { (controller, method) ->
                method.parameters
                    .filter { it.type == Principal::class.java || it.isAnnotationPresent(AuthenticationPrincipal::class.java) }
                    .map { "${controller.simpleName}.${method.name}(${it.type.simpleName})" }
            }

        assertNoViolations(violations, "Principal·@AuthenticationPrincipal 대신 Authentication + getUserId()를 쓴다")
    }

    @Test
    fun `Authentication 파라미터의 nullable은 @SecurityRequirements가 붙은 공개 엔드포인트에만 쓴다`() {
        val violations =
            endpoints().mapNotNull { (controller, method) ->
                val nullableAuthentication =
                    method.kotlinFunction?.parameters.orEmpty().any {
                        it.type.classifier == Authentication::class && it.type.isMarkedNullable
                    }
                val isPublic = AnnotatedElementUtils.findMergedAnnotation(method, SecurityRequirements::class.java) != null
                if (nullableAuthentication && !isPublic) "${controller.simpleName}.${method.name}" else null
            }

        assertNoViolations(
            violations,
            "인증 필수 엔드포인트는 authentication: Authentication(non-null)로 받는다. 비로그인을 허용한다면 " +
                "@SecurityRequirements를 붙이고 SecurityConfig 공개 경로에도 추가한다",
        )
    }

    @Test
    fun `엔드포인트는 ApiResponse 또는 ResponseEntity로 감싼 ApiResponse를 반환한다`() {
        val violations =
            endpoints().mapNotNull { (controller, method) ->
                if (returnsApiResponse(method)) null else "${controller.simpleName}.${method.name}: ${method.genericReturnType.typeName}"
            }

        assertNoViolations(violations, "ApiResponse(HttpStatus.X.value(), 메시지, data)로 감싼다(본문이 없으면 Unit)")
    }

    private fun returnsApiResponse(method: Method): Boolean {
        val type = method.genericReturnType
        if (type == ApiResponse::class.java) return true
        if (type !is ParameterizedType) return false
        if (type.rawType == ApiResponse::class.java) return true
        if (type.rawType != ResponseEntity::class.java) return false
        val body = type.actualTypeArguments.first()
        return body == ApiResponse::class.java || (body is ParameterizedType && body.rawType == ApiResponse::class.java)
    }

    private fun assertNoViolations(violations: List<String>, howToFix: String) {
        assertTrue(
            violations.isEmpty(),
            buildString {
                appendLine("규칙을 어긴 엔드포인트가 ${violations.size}개 있다:")
                violations.forEach { appendLine("  - $it") }
                appendLine()
                appendLine(howToFix)
            },
        )
    }

    private fun endpoints(): List<Pair<Class<*>, Method>> {
        val scanner = ClassPathScanningCandidateComponentProvider(false)
        scanner.addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        val endpoints =
            scanner.findCandidateComponents(basePackage)
                .mapNotNull { it.beanClassName }
                .map { Class.forName(it) }
                .sortedBy { it.name }
                .flatMap { controller ->
                    controller.declaredMethods
                        .filter { !it.isSynthetic && AnnotatedElementUtils.hasAnnotation(it, RequestMapping::class.java) }
                        .sortedBy { it.name }
                        .map { controller to it }
                }

        assertTrue(
            endpoints.size >= minimumEndpointCount,
            "엔드포인트를 최소 $minimumEndpointCount 개 찾아야 하는데 ${endpoints.size}개만 찾았다. basePackage($basePackage)를 확인한다.",
        )
        return endpoints
    }
}
