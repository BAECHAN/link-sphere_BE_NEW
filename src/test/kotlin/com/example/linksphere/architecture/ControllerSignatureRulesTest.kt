package com.example.linksphere.architecture

import com.example.linksphere.global.common.ApiResponse
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
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
 * - 응답은 `ApiResponse`로 감싼다. data에 이름 없는 `Map`을 쓰지 않는다(OpenAPI 스키마 이름이 생기지 않는다)
 * - `ResponseEntity<ApiResponse<…>>`는 응답 헤더(쿠키·캐시)를 붙이는 엔드포인트만 쓴다
 * - 본문 status가 201이면 HTTP 상태도 201이다(`@ResponseStatus(HttpStatus.CREATED)`) - 반대도 같다
 *
 * 마지막 두 규칙은 메서드 본문(어떤 상수를 읽고 어떤 빌더 메서드를 부르는지)을 봐야 해서 ArchUnit 임포터로 바이트코드를 본다.
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

    @Test
    fun `응답 데이터에 이름 없는 Map을 쓰지 않는다`() {
        val violations =
            endpoints().mapNotNull { (controller, method) ->
                val data = apiResponseDataType(method)
                val raw = (data as? ParameterizedType)?.rawType ?: data
                if (raw is Class<*> && Map::class.java.isAssignableFrom(raw)) "${controller.simpleName}.${method.name}" else null
            }

        assertNoViolations(violations, "data는 *DTO.kt의 이름 있는 *Response로 감싼다(예: CommentLikeResponse)")
    }

    // 2026-10-26 전에는 생성 5곳이 본문만 201이고 HTTP는 200이었다 - RFC 9110 §9.3.3은 자원을 만든 POST에 201을 권한다
    @Test
    fun `본문 status가 201인 엔드포인트는 HTTP 상태도 201이다`() {
        val violations =
            endpointMethodsInBytecode().mapNotNull { method ->
                val bodyIsCreated =
                    method.fieldAccesses.any { it.target.owner.isEquivalentTo(HttpStatus::class.java) && it.target.name == HttpStatus.CREATED.name }
                val statusIsCreated =
                    AnnotatedElementUtils.findMergedAnnotation(method.reflect(), ResponseStatus::class.java)?.code == HttpStatus.CREATED
                if (bodyIsCreated == statusIsCreated) null else "${method.owner.simpleName}.${method.name}"
            }

        assertNoViolations(violations, "본문에 HttpStatus.CREATED를 쓰면 @ResponseStatus(HttpStatus.CREATED)를 붙인다(반대도 같다)")
    }

    @Test
    fun `ResponseEntity는 응답 헤더를 붙이는 엔드포인트만 반환한다`() {
        val violations =
            endpointMethodsInBytecode()
                .filter { it.rawReturnType.isEquivalentTo(ResponseEntity::class.java) }
                .filterNot { setsResponseHeader(it) || it.callsSameClassMethodThat(::setsResponseHeader) }
                .map { "${it.owner.simpleName}.${it.name}" }

        assertNoViolations(violations, "헤더를 붙이지 않으면 ApiResponse를 그대로 반환한다(상태는 @ResponseStatus)")
    }

    private fun setsResponseHeader(method: JavaMethod): Boolean = method.methodCallsFromSelf.any {
        it.target.owner.isAssignableTo(ResponseEntity.HeadersBuilder::class.java) && it.target.name in headerSettingMethods
    }

    // 쿠키를 붙이는 공통 private 함수(AuthController.createCookieResponse)를 거치는 경우 - 한 단계만 따라간다
    private fun JavaMethod.callsSameClassMethodThat(predicate: (JavaMethod) -> Boolean): Boolean = methodCallsFromSelf
        .filter { it.target.owner == owner }
        .mapNotNull { it.target.resolveMember().orElse(null) }
        .any(predicate)

    private val headerSettingMethods = setOf("header", "headers", "cacheControl")

    private fun endpointMethodsInBytecode(): List<JavaMethod> {
        val methods =
            ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages(basePackage)
                .filter { it.isAnnotatedWith(RestController::class.java) }
                .flatMap { controller -> controller.methods.filter { it.isMetaAnnotatedWith(RequestMapping::class.java) } }
                .sortedBy { it.fullName }

        assertTrue(
            methods.size >= minimumEndpointCount,
            "엔드포인트를 최소 $minimumEndpointCount 개 찾아야 하는데 ${methods.size}개만 찾았다(바이트코드 임포트).",
        )
        return methods
    }

    private fun apiResponseDataType(method: Method): java.lang.reflect.Type? {
        val type = method.genericReturnType as? ParameterizedType ?: return null
        val apiResponse =
            if (type.rawType == ResponseEntity::class.java) type.actualTypeArguments.first() as? ParameterizedType else type
        if (apiResponse?.rawType != ApiResponse::class.java) {
            return null
        }

        return apiResponse.actualTypeArguments.first()
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
