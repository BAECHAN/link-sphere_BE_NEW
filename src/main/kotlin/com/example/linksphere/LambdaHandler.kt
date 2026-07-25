package com.example.linksphere

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
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
        private val logger = org.slf4j.LoggerFactory.getLogger(LambdaHandler::class.java)
        private val mapper = ObjectMapper()
        private val mockMvc: MockMvc

        // context-path(/api)는 handleRequest에서 제거되므로 MockMvc에는 서블릿 경로만 전달한다.
        // 둘 다 읽기 전용이고 SecurityConfig에서 permitAll 대상이라 부작용이 없다.
        private val WARMUP_PATHS = listOf("/actuator/health", "/common/category-options")
        private const val WARMUP_ITERATIONS = 3

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
                override fun createApplicationContext(): ConfigurableApplicationContext = AnnotationConfigServletWebServerApplicationContext()
            }
            app.webApplicationType = WebApplicationType.SERVLET
            val ctx = app.run()
            // webAppContextSetup()만으로는 FilterChainProxy(Spring Security)가 MockMvc 필터 체인에
            // 자동 포함되지 않는다. 명시적으로 추가해야 JwtAuthenticationFilter 등 보안 필터가 실행된다.
            val securityFilter = ctx.getBean("springSecurityFilterChain") as jakarta.servlet.Filter
            val builder = MockMvcBuilders.webAppContextSetup(ctx as WebApplicationContext)
            builder.addFilters<org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder>(securityFilter)
            mockMvc = builder.build()

            warmUp()
        }

        /**
         * SnapStart 체크포인트 이전(init 단계)에 실제 요청을 흘려보내 초기화 비용을 스냅샷에 굽는다.
         *
         * 이 과정이 없으면 DispatcherServlet 초기화, Security 필터 체인 첫 통과,
         * Hibernate 메타모델·쿼리플랜 생성, HikariCP 커넥션 확보가 전부 복원 이후 첫 요청으로 밀린다.
         * 실측상 restore 자체는 약 0.65초인데 그 뒤 첫 요청이 약 2.9초였던 원인이 이것이다.
         *
         * DataSourceCracHook의 suspendPool()은 체크포인트 시점에 호출되므로 이 DB 워밍업과 충돌하지 않는다.
         * 워밍업이 실패해도 부팅은 계속한다 — 배포 시점에 DB가 닿지 않아도 Lambda는 기동되어야 한다.
         */
        private fun warmUp() {
            // JIT를 인터프리터 단계 밖으로 밀어내기 위해 반복 호출한다
            repeat(WARMUP_ITERATIONS) {
                WARMUP_PATHS.forEach { path ->
                    try {
                        mockMvc.perform(MockMvcRequestBuilders.get(path)).andReturn()
                    } catch (e: Exception) {
                        logger.warn("Warmup request failed: {} ({})", path, e.message)
                    }
                }
            }
        }
    }

    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        val event = mapper.readTree(input)
        // rawPath에는 CloudFront가 forward한 전체 경로(/api/auth/login)가 담겨있다.
        // MockMvc는 Tomcat과 달리 context-path(/api)를 자동으로 스트립하지 않으므로,
        // Spring Security의 requestMatchers("/auth/login")가 /api/auth/login과 매칭 실패해 401이 된다.
        // context-path를 제거한 서블릿 경로만 MockMvc에 전달한다.
        val rawPath = event.get("rawPath")?.asText() ?: "/"
        val path = rawPath.removePrefix("/api").ifEmpty { "/" }
        val rawQuery = event.get("rawQueryString")?.asText()?.takeIf { it.isNotEmpty() }
        val method = event.at("/requestContext/http/method").asText("GET")
        val body = event.get("body")?.asText()
        val isBase64 = event.get("isBase64Encoded")?.asBoolean() ?: false

        val uri = if (rawQuery != null) URI.create("$path?$rawQuery") else URI.create(path)
        val requestBuilder = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), uri)

        var cookieHeader: String? = null
        event.get("headers")?.let { headers ->
            val names = headers.fieldNames()
            while (names.hasNext()) {
                val key = names.next()
                val value = headers.get(key).asText()
                if (key.equals("cookie", ignoreCase = true)) {
                    cookieHeader = value
                } else {
                    requestBuilder.header(key, value)
                }
            }
        }
        // Lambda 이벤트의 cookies 배열 우선 처리 (AllViewerExceptHostHeader 정책에서 cookies 필드로 전달될 수 있음)
        val cookiesNode = event.get("cookies")
        if (cookiesNode != null && cookiesNode.isArray && cookiesNode.size() > 0) {
            cookieHeader = cookiesNode.joinToString("; ") { it.asText() }
        }
        // MockMvc는 Cookie 헤더를 자동 파싱하지 않으므로 명시적으로 Cookie 객체로 변환해야 @CookieValue가 동작한다.
        cookieHeader?.split(";")?.forEach { part ->
            val trimmed = part.trim()
            val idx = trimmed.indexOf('=')
            if (idx > 0) {
                requestBuilder.cookie(Cookie(trimmed.substring(0, idx).trim(), trimmed.substring(idx + 1).trim()))
            }
        }

        if (!body.isNullOrEmpty()) {
            val bytes = if (isBase64) Base64.getDecoder().decode(body) else body.toByteArray()
            requestBuilder.content(bytes)
        }

        val result = mockMvc.perform(requestBuilder).andReturn()
        val response = result.response
        val responseHeaders = response.headerNames.associateWith { response.getHeader(it) }

        mapper.writeValue(
            output,
            mapOf(
                "statusCode" to response.status,
                "headers" to responseHeaders,
                "body" to response.contentAsString,
            ),
        )
    }
}
