package com.example.linksphere

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Base64

/**
 * AWS Lambda SnapStart 핸들러.
 *
 * Tomcat 소켓을 사용하지 않고 MockMvc로 DispatcherServlet을 직접 호출한다.
 *
 * Tomcat 방식을 사용하지 않는 이유:
 * - SnapStart CRaC 체크포인트는 열린 소켓(Tomcat 8080, HikariCP DB)이 있으면 State:Failed
 * - CRaC restore 후 Tomcat이 8080 포트에 재바인딩하지 못하는 Lambda 환경 제약
 *
 * 동작 흐름:
 * 1. Init phase: companion object init에서 Spring Boot 시작 → SnapStart 스냅샷 저장
 * 2. Cold start: 스냅샷 복원 → handleRequest() 즉시 호출 (Spring 재시작 없음)
 * 3. 요청 처리: Lambda 이벤트(rawPath, headers, body) → MockMvc → DispatcherServlet → 응답
 */
class LambdaHandler : RequestStreamHandler {

    companion object {
        private val mapper = ObjectMapper()
        private val mockMvc: MockMvc

        init {
            // Lambda 시스템 classloader에는 shadow JAR의 jakarta.servlet.Servlet이 없다.
            // WebApplicationType.deduceFromClasspath()가 NONE으로 감지되는 것을 막기 위해
            // shadow JAR의 classloader로 교체한다.
            Thread.currentThread().contextClassLoader = LambdaHandler::class.java.classLoader

            // createApplicationContext()를 오버라이드해 AnnotationConfigServletWebServerApplicationContext를 직접 생성한다.
            // Shadow JAR에서 spring.factories가 올바르게 병합되지 않으면 ApplicationContextFactory 조회가 실패해
            // AnnotationConfigApplicationContext(비웹)로 폴백되고 WebApplicationContext 캐스팅에서 오류가 난다.
            // 이 오버라이드는 spring.factories 조회 자체를 건너뛰는 이중 방어책이다.
            val app = object : SpringApplication(LinkSphereBeApplication::class.java) {
                override fun createApplicationContext(): ConfigurableApplicationContext =
                    AnnotationConfigServletWebServerApplicationContext()
            }
            app.webApplicationType = WebApplicationType.SERVLET
            val ctx = app.run()
            // webAppContextSetup()만으로는 FilterChainProxy(Spring Security)가 MockMvc 필터 체인에
            // 자동 포함되지 않는다. 명시적으로 추가해야 JwtAuthenticationFilter 등 보안 필터가 실행된다.
            val securityFilter = ctx.getBean("springSecurityFilterChain") as jakarta.servlet.Filter
            val builder = MockMvcBuilders.webAppContextSetup(ctx as WebApplicationContext)
            builder.addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(securityFilter)
            mockMvc = builder.build()
        }
    }

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val event = mapper.readTree(input)
        val path = event.get("rawPath")?.asText() ?: "/"
        val rawQuery = event.get("rawQueryString")?.asText()?.takeIf { it.isNotEmpty() }
        val method = event.at("/requestContext/http/method").asText("GET")
        val body = event.get("body")?.asText()
        val isBase64 = event.get("isBase64Encoded")?.asBoolean() ?: false

        val uri = if (rawQuery != null) URI.create("$path?$rawQuery") else URI.create(path)
        val requestBuilder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), uri)

        event.get("headers")?.let { headers ->
            val names = headers.fieldNames()
            while (names.hasNext()) {
                val key = names.next()
                requestBuilder.header(key, headers.get(key).asText())
            }
        }

        if (!body.isNullOrEmpty()) {
            val bytes = if (isBase64) Base64.getDecoder().decode(body) else body.toByteArray()
            requestBuilder.content(bytes)
        }

        val result = mockMvc.perform(requestBuilder).andReturn()
        val response = result.response
        val responseHeaders = response.headerNames.associateWith { response.getHeader(it) }

        mapper.writeValue(output, mapOf(
            "statusCode" to response.status,
            "headers" to responseHeaders,
            "body" to response.contentAsString
        ))
    }
}
