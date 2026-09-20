package com.example.linksphere.global.config

import com.fasterxml.jackson.databind.JavaType
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaGetter

/**
 * springdoc(swagger-core)은 Kotlin의 `String?` 같은 nullable 타입을 "required 목록에서
 * 빼는 것"으로만 표현하고 스키마에 `nullable: true`를 붙이지 않는다. 그런데 실제 런타임은
 * Jackson 기본 설정(JsonInclude.ALWAYS)이라 값이 없으면 키가 생략되는 게 아니라 값이
 * `null`로 온다 — 즉 스펙은 "키가 없을 수 있다"고 말하지만 실제로는 "키는 항상 있고 값이
 * null일 수 있다"이다. FE가 이 스펙으로 타입을 생성하면 `string | undefined`가 되어
 * 실제 `null` 응답과 어긋난다(FE openapi-codegen 도입 계획 Phase 0.5, 2026-09-20 발견).
 *
 * 이름이 있는(named) 모델(PostResponse 등)이 여러 곳에서 참조되면 swagger-core는 최초 1회만
 * 프로퍼티가 채워진 완전한 Schema를 만들어 openAPI.components.schemas 에 등록하고, 이후
 * 참조 지점에는 `{$ref: "#/components/schemas/PostResponse"}` 만 있는 빈 Schema를 돌려준다
 * (properties=null). ModelConverter.resolve()는 이 $ref 전용 Schema만 보게 되는 경우가
 * 대부분이라 여기서 직접 nullable을 붙일 수 없다(2026-09-20 로컬 bootRun으로 실측 확인 — 첫
 * 구현은 이 경로로 시도했다가 전부 무효였다).
 *
 * 그래서 두 확장점을 나눠 쓴다:
 * 1. [NullableAwareModelConverter] — swagger-core가 모든 타입을 순회할 때(resolve) 호출되므로
 *    여기서 실제 Kotlin Class를 얻어 "스키마 이름 → nullable JSON 프로퍼티명 집합"만 계산해
 *    저장해 둔다. 이 시점엔 아직 컴포넌트 스키마가 다 안 모여 있어도 상관없다.
 * 2. [NullableAwareOpenApiCustomizer] — springdoc이 스펙 생성을 끝낸 뒤 호출되므로
 *    openAPI.components.schemas 가 이름 기준으로 전부 채워져 있다. 1번이 모아둔 이름별
 *    nullable 집합을 여기서 실제 프로퍼티 Schema에 적용한다.
 */
@Component
class NullableAwareModelConverter : ModelConverter {
    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>,
    ): Schema<*>? {
        val resolved = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val kClass = rawClassOf(type)?.kotlin

        if (kClass != null) {
            val schemaName = kClass.simpleName

            if (schemaName != null && !nullablePropertiesBySchemaName.containsKey(schemaName)) {
                nullablePropertiesBySchemaName[schemaName] = nullableJsonPropertyNames(kClass)
            }
        }

        return resolved
    }

    companion object {
        /** 스키마 이름 → nullable인 JSON 프로퍼티명 집합. OpenApiCustomizer가 최종 적용한다. */
        val nullablePropertiesBySchemaName = ConcurrentHashMap<String, Set<String>>()

        private fun nullableJsonPropertyNames(kClass: kotlin.reflect.KClass<*>): Set<String> = try {
            kClass.memberProperties
                .filter { it.returnType.isMarkedNullable }
                // Jackson 프로퍼티명은 getter 기준이라 Kotlin 프로퍼티명과 항상 같다는
                // 보장이 없다 — javaGetter 이름에서 get/is 접두사를 벗겨 실제 JSON
                // 키(camelCase)를 복원한다.
                .mapNotNull { it.javaGetter?.name?.let(::jsonPropertyNameFromGetter) }
                .toSet()
        } catch (_: Exception) {
            // Kotlin 메타데이터가 없는 클래스(JDK 타입 등)에 kotlin-reflect를 쓰면 던진다.
            // 이 컨버터가 관여할 대상이 아니므로 빈 집합으로 취급한다.
            emptySet()
        }

        /**
         * type.type은 호출 경로에 따라 원시 java.lang.Class로 오기도 하고(요청 바디처럼
         * 컨트롤러 시그니처에서 직접 스캔되는 타입), Jackson의 JavaType(SimpleType 등)으로
         * 감싸져 오기도 한다(ApiResponse<T>의 T처럼 제네릭 타입 인자로 다시 해석되는 경우).
         * 실측(2026-09-20 로컬 bootRun): PostCreateRequest는 전자, PostResponse는 후자였다.
         */
        private fun rawClassOf(type: AnnotatedType): Class<*>? = when (val t = type.type) {
            is Class<*> -> t
            is JavaType -> t.rawClass
            else -> null
        }

        private fun jsonPropertyNameFromGetter(getterName: String): String? {
            val stripped = when {
                getterName.startsWith("get") -> getterName.removePrefix("get")
                getterName.startsWith("is") -> return getterName
                else -> return null
            }

            if (stripped.isEmpty()) {
                return null
            }

            return stripped.replaceFirstChar { it.lowercase() }
        }
    }
}

@Component
class NullableAwareOpenApiCustomizer : OpenApiCustomizer {
    override fun customise(openApi: OpenAPI) {
        val schemas = openApi.components?.schemas ?: return

        for ((schemaName, schema) in schemas) {
            val nullableNames =
                NullableAwareModelConverter.nullablePropertiesBySchemaName[schemaName] ?: continue
            val properties = schema.properties ?: continue

            for (name in nullableNames) {
                properties[name]?.nullable = true
            }
        }
    }
}
