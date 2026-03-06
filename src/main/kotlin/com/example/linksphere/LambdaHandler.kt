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
 * Spring Boot + AWS Lambda SnapStart 핸들러 (MockMvc 방식).
 *
 * Tomcat 소켓 없이 Spring DispatcherServlet을 직접 호출합니다.
 * CRaC restore 후 Tomcat 재바인딩 실패 문제를 우회합니다.
 *
 * 동작 방식:
 * 1. Lambda init phase: Spring Boot 시작, MockMvc 초기화 → SnapStart 스냅샷에 포함
 * 2. CRaC restore: Spring context는 그대로 복원, MockMvc 즉시 사용 가능
 * 3. 요청 시: Lambda Function URL 이벤트 → MockMvc → DispatcherServlet → 응답
 */
class LambdaHandler : RequestStreamHandler {

    companion object {
        private val mapper = ObjectMapper()
        private val mockMvc: MockMvc

        init {
            // Lambda runtime의 thread context classloader에는 jakarta.servlet.Servlet이 없음
            // → WebApplicationType.deduceFromClasspath()가 NONE으로 감지됨
            // CustomerClassLoader로 교체하면 shadow JAR의 jakarta.servlet.Servlet을 찾아 SERVLET 타입으로 감지
            Thread.currentThread().contextClassLoader = LambdaHandler::class.java.classLoader
            // spring.factories 조회 없이 직접 AnnotationConfigServletWebServerApplicationContext 생성
            // (Shadow JAR에서 spring.factories 병합 실패 시 AnnotationConfigApplicationContext 폴백 방지)
            val app = object : SpringApplication(LinkSphereBeApplication::class.java) {
                override fun createApplicationContext(): ConfigurableApplicationContext =
                    AnnotationConfigServletWebServerApplicationContext()
            }
            app.webApplicationType = WebApplicationType.SERVLET
            val ctx = app.run()
            mockMvc = MockMvcBuilders
                .webAppContextSetup(ctx as WebApplicationContext)
                .build()
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
