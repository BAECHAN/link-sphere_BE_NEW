package com.example.linksphere.global.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

private data class SampleModel(
    val required: String,
    val optional: String?,
    val flag: Boolean,
)

class NullablePropertyRegistryTest {

    @Test
    fun `registerIfAbsent 은 처음 등록한 값을 반환한다`() {
        val registry = NullablePropertyRegistry()

        registry.registerIfAbsent("Sample") { setOf("a", "b") }

        assertEquals(setOf("a", "b"), registry.get("Sample"))
    }

    @Test
    fun `registerIfAbsent 은 이미 등록된 이름이면 다시 계산하지 않는다`() {
        val registry = NullablePropertyRegistry()
        registry.registerIfAbsent("Sample") { setOf("first") }

        registry.registerIfAbsent("Sample") { setOf("second") }

        assertEquals(setOf("first"), registry.get("Sample"))
    }

    @Test
    fun `등록되지 않은 이름은 null 을 반환한다`() {
        val registry = NullablePropertyRegistry()

        assertNull(registry.get("Unknown"))
    }
}

class NullableAwareModelConverterTest {

    private val registry = NullablePropertyRegistry()
    private val converter = NullableAwareModelConverter(registry)
    private val context = mock(ModelConverterContext::class.java)

    private fun emptyChain(): MutableIterator<ModelConverter> = mutableListOf<ModelConverter>().iterator()

    @Test
    fun `resolve 는 nullable 프로퍼티 이름만 registry 에 기록한다`() {
        val type = AnnotatedType(SampleModel::class.java)

        converter.resolve(type, context, emptyChain())

        assertEquals(setOf("optional"), registry.get("SampleModel"))
    }

    @Test
    fun `resolve 는 Boolean 프로퍼티의 is 접두사 getter 를 그대로 JSON 키로 쓴다`() {
        val type = AnnotatedType(SampleModel::class.java)

        converter.resolve(type, context, emptyChain())

        // flag: Boolean 은 nullable 이 아니므로 optional 집합에는 포함되지 않지만,
        // isFlag 같은 getter 이름이 잘못 파싱돼 엉뚱한 키로 섞여 들어가지 않는지 확인한다.
        assertEquals(setOf("optional"), registry.get("SampleModel"))
    }

    @Test
    fun `resolve 는 같은 스키마 이름을 다시 계산하지 않는다`() {
        registry.registerIfAbsent("SampleModel") { setOf("preset") }
        val type = AnnotatedType(SampleModel::class.java)

        converter.resolve(type, context, emptyChain())

        assertEquals(setOf("preset"), registry.get("SampleModel"))
    }
}

class NullableAwareOpenApiCustomizerTest {

    private val registry = NullablePropertyRegistry()
    private val customizer = NullableAwareOpenApiCustomizer(registry)

    @Test
    fun `registry 에 등록된 프로퍼티만 nullable 로 표시한다`() {
        registry.registerIfAbsent("SampleModel") { setOf("optional") }
        val schema = Schema<Any>()
        schema.properties = linkedMapOf<String, Schema<*>>("required" to StringSchema(), "optional" to StringSchema())
        val openApi = OpenAPI().components(Components().schemas(linkedMapOf("SampleModel" to schema)))

        customizer.customise(openApi)

        assertEquals(true, schema.properties["optional"]!!.nullable)
        assertNull(schema.properties["required"]!!.nullable)
    }

    @Test
    fun `registry 에 없는 스키마는 건드리지 않는다`() {
        val schema = Schema<Any>()
        schema.properties = linkedMapOf<String, Schema<*>>("required" to StringSchema())
        val openApi = OpenAPI().components(Components().schemas(linkedMapOf("Untracked" to schema)))

        customizer.customise(openApi)

        assertNull(schema.properties["required"]!!.nullable)
    }
}
