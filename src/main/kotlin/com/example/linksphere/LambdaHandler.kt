package com.example.linksphere

import com.amazonaws.serverless.proxy.InitializationWrapper
import com.amazonaws.serverless.proxy.internal.LambdaContainerHandler
import com.amazonaws.serverless.proxy.model.AwsProxyResponse
import com.amazonaws.serverless.proxy.model.HttpApiV2HttpContext
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequestContext
import com.amazonaws.serverless.proxy.spring.SpringBootLambdaContainerHandler
import com.amazonaws.serverless.proxy.spring.SpringBootProxyHandlerBuilder
import com.amazonaws.services.lambda.runtime.ClientContext
import com.amazonaws.services.lambda.runtime.CognitoIdentity
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.LambdaLogger
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import com.example.linksphere.domain.auth.AccountPurgeService
import com.example.linksphere.domain.comment.CommentPostProcessEvent
import com.example.linksphere.domain.comment.CommentPostProcessService
import com.example.linksphere.domain.feed.FeedCrawlService
import com.example.linksphere.domain.feed.FeedItemJobEvent
import com.example.linksphere.domain.post.PostAIService
import com.example.linksphere.domain.post.PostCreatedEvent
import com.example.linksphere.domain.upload.OrphanImageGcService
import com.example.linksphere.global.common.EmailMaskingLogCustomizer
import com.example.linksphere.global.common.RateLimitService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.MDC
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.context.support.WebApplicationContextUtils
import software.amazon.awssdk.core.warmup.SdkWarmUp
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.ses.SesClient
import java.io.InputStream
import java.io.OutputStream

/**
 * AWS Lambda SnapStart 핸들러.
 *
 * Function URL 이벤트를 serverless-java-container(2.1.7)가 서블릿 요청으로 바꾸고, 서블릿 필터 전체와 DispatcherServlet을
 * 거쳐 응답을 만든다. Tomcat은 만들지 않는다 — 라이브러리가 자기 웹 서버 팩토리(`ServerlessServletEmbeddedServerFactory`)를
 * 부트 소스로 넣어 내장 Tomcat 팩토리 대신 쓰므로 포트를 열지 않는다(init 블록 주석 참고).
 *
 * 서빙 경로의 경위(Lambda에서 Tomcat을 못 써서가 아니다):
 * - 2026-03: 체크포인트 State:Failed는 org.crac 의존성으로 풀었고, 복원 뒤 Tomcat이 8080에 재바인딩하지 못한다고 보고 MockMvc 경로를
 *   만들었다. 재바인딩 실패의 원인은 추정이었다(docs/DEPLOY.md 시행착오 2·3).
 * - 2026-07-25: 그때까지 실제 트래픽은 Lambda Web Adapter를 거쳐 Tomcat이 복원 뒤에도 처리하고 있었다. init에서 MockMvc를 돌리는 워밍업이
 *   이 어댑터와 충돌해, 어댑터를 없애고 MockMvc로 서빙을 옮겼다(docs/PERFORMANCE.md 5장).
 * - 2026-11-10: MockMvc는 테스트 도구라 보안 필터만 돌았고, 쿼리·헤더를 Tomcat과 다르게 읽은 일이 세 번 있었다(`+` 검색 #177,
 *   `Vary` 첫 값 #121, HSTS #40). Tomcat과의 비교 실험과 콜드 스타트 측정을 거쳐 이 라이브러리로 옮겼다
 *   (docs/plans/2026-11-10-serving-path-switch.md).
 *
 * 동작 흐름:
 * 1. Init phase: companion object init에서 Spring Boot 시작 → SnapStart 스냅샷 저장
 * 2. Cold start: 스냅샷 복원 → handleRequest() 즉시 호출 (Spring 재시작 없음)
 * 3. 요청 처리: Lambda 이벤트 → 내부 작업 분기·Origin 가드 → serverless-java-container → 필터 체인·DispatcherServlet → 응답
 */
// 스냅샷 전 워밍업 요청에 넘기는 빈 Context. 이 시점에는 실제 호출이 없다.
private object WarmUpContext : Context {
    override fun getAwsRequestId() = "warmup"
    override fun getLogGroupName() = ""
    override fun getLogStreamName() = ""
    override fun getFunctionName() = ""
    override fun getFunctionVersion() = ""
    override fun getInvokedFunctionArn() = ""
    override fun getIdentity(): CognitoIdentity? = null
    override fun getClientContext(): ClientContext? = null
    override fun getRemainingTimeInMillis() = 0
    override fun getMemoryLimitInMB() = 0
    override fun getLogger(): LambdaLogger? = null
}

class LambdaHandler : RequestStreamHandler {

    companion object {
        private val logger = org.slf4j.LoggerFactory.getLogger(LambdaHandler::class.java)

        // AI 작업 페이로드(PostCreatedEvent)의 Kotlin data class 역직렬화에 KotlinModule이 필요하다.
        // readTree/writeValue(Map) 용도에는 영향 없어 기존 동작은 그대로 유지된다.
        private val mapper = jacksonObjectMapper()
        private val container: SpringBootLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse>
        private val applicationContext: ConfigurableApplicationContext

        // 실제 요청처럼 context-path(/api)를 붙인 경로다(라이브러리가 떼어 낸다). 실제 매핑과 정확히 일치해야 한다 —
        // 404가 나면 DispatcherServlet만 데워지고 정작 중요한 Hibernate·HikariCP는 건너뛴다(CategoryController는
        // category-option 단수형).
        private val WARMUP_PATHS = listOf("/api/actuator/health", "/api/common/category-option")
        private const val REQUEST_ID_MDC_KEY = "awsRequestId"
        private const val WARMUP_ITERATIONS = 3

        init {
            // Lambda 시스템 classloader에는 shadow JAR의 jakarta.servlet.Servlet이 없다.
            // WebApplicationType.deduceFromClasspath()가 NONE으로 감지되는 것을 막기 위해
            // shadow JAR의 classloader로 교체한다.
            Thread.currentThread().contextClassLoader = LambdaHandler::class.java.classLoader

            // 로그는 Lambda에서만 logstash JSON 한 줄로 낸다(spring-boot 3.5 structured logging). Text 로그는 RequestId가
            // 없고 예외 1건의 스택이 CloudWatch 이벤트 약 110개로 쪼개졌다(2026-10-26 운영 로그). JSON은 스택을
            // stack_trace 필드 한 줄에 담고 MDC(아래 handleRequest의 awsRequestId)를 최상위 키로 싣는다. 로컬 로그는 그대로다.
            // 이메일 가림과 TLS 강제도 운영에만 건다 - pgjdbc 기본값은 PREFER라(42.7.11 SslMode.of) TLS를 못 쓰면 평문으로
            // 넘어가고, 테스트 컨테이너와 로컬 DB는 TLS가 없을 수 있다(docs/DATA-PROTECTION.md).
            // 라이브러리가 SpringApplicationBuilder.run()을 인자 없이 부르므로(SpringBootLambdaContainerHandler.initialize)
            // 커맨드라인 인자 대신 시스템 프로퍼티로 넣는다. 시스템 프로퍼티도 application.yml보다 우선한다.
            System.setProperty("logging.structured.format.console", "logstash")
            System.setProperty("logging.structured.json.customizer", EmailMaskingLogCustomizer::class.java.name)
            System.setProperty("spring.datasource.hikari.data-source-properties.sslmode", "require")

            // 기본값이 Tomcat·예전 경로와 다른 설정만 바꾼다(core 2.1.7 ContainerConfig.defaultConfig):
            // - rawPath의 /api를 떼고 요청의 context-path를 /api로 보여 준다. 보안 필터의 requestMatchers("/auth/login")가
            //   서블릿 경로로 맞는다.
            // - 필터 예외를 502 응답으로 바꾸지 않고 함수 실패로 남긴다. DB가 죽어 인증 필터가 던지면 Errors 알람이 울려야 한다.
            // - 쿼리 키 대소문자를 Tomcat처럼 가린다(기본값은 가리지 않는다).
            // - charset 없는 본문을 UTF-8로 읽고 쓴다(기본값은 ISO-8859-1).
            LambdaContainerHandler.getContainerConfig().apply {
                setServiceBasePath("/api")
                setStripBasePath(true)
                setDisableExceptionMapper(true)
                setQueryStringCaseSensitive(true)
                setDefaultContentCharset("UTF-8")
            }

            container =
                SpringBootProxyHandlerBuilder<HttpApiV2ProxyRequest>()
                    .defaultHttpApiV2Proxy()
                    .initializationWrapper(InitializationWrapper())
                    .servletApplication()
                    .springBootApplication(LinkSphereBeApplication::class.java)
                    .buildAndInitialize()
            // 요청마다 클라이언트 IP를 INFO로 남기는 접속 로그를 끈다. 예전 경로에도 접속 로그는 없었다
            // (null이면 건너뛴다, core 2.1.7 LambdaContainerHandler.proxy).
            container.setLogFormatter(null)
            applicationContext =
                WebApplicationContextUtils.getRequiredWebApplicationContext(container.servletContext) as ConfigurableApplicationContext

            warmUp()
            warmUpAwsSdk()
        }

        /**
         * SnapStart 체크포인트 이전(init 단계)에 실제 요청을 흘려보내 처음 쓸 때 하는 준비를 스냅샷에 담는다.
         *
         * 이 과정이 없으면 처음 쓰는 클래스 로딩, Security 필터 체인 첫 통과, Hibernate 쿼리 처리가
         * 복원 이후 첫 요청으로 밀린다. 지나간 경로만 담긴다. DispatcherServlet 초기화와 Hibernate 메타모델은
         * 이 워밍업과 무관하게 먼저 끝난다. 항목별 근거는 docs/PERFORMANCE.md 3장 참고.
         *
         * 주의 — 2026-07-25에 한 번 실패했다. 당시에는 Lambda Web Adapter 레이어가 붙어 있어
         * Tomcat이 실트래픽을 처리했는데, init에서 MockMvc를 건드리자 복원 후 LWA가 8080에
         * 접속하지 못해 호출 전체가 502로 실패했다. 워밍업은 실제 서빙 경로(지금은 serverless-java-container)를
         * 지날 때만 성립한다. 자세한 경위는 docs/PERFORMANCE.md 5장 참고.
         *
         * HikariCP 풀은 Spring Boot의 HikariCheckpointRestoreLifecycle이 체크포인트 직전에 멈추고
         * 커넥션을 닫으므로(복원 때 다시 연다) 커넥션은 스냅샷에 담기지 않고 이 DB 워밍업과 충돌하지도 않는다.
         * 워밍업이 실패해도 부팅은 계속한다 — 배포 시점에 DB가 닿지 않아도 Lambda는 기동되어야 한다.
         */
        private fun warmUp() {
            // 횟수는 측정으로 정한 값이 아니다 - 근거와 한계는 docs/PERFORMANCE.md 4.1 참고
            repeat(WARMUP_ITERATIONS) {
                WARMUP_PATHS.forEach { path ->
                    try {
                        val status = container.proxy(warmUpRequest(path), WarmUpContext).statusCode
                        // 경로 오타로 404가 나도 조용히 지나가면 "워밍업했다"는 착각만 남는다
                        if (status !in 200..299) {
                            logger.warn("Warmup did not succeed: {} -> {}", path, status)
                        }
                    } catch (e: Exception) {
                        logger.warn("Warmup failed: {} ({})", path, e.message)
                    }
                }
            }
        }

        private fun warmUpRequest(path: String): HttpApiV2ProxyRequest {
            val http = HttpApiV2HttpContext()
            http.method = "GET"
            http.path = path
            http.protocol = "HTTP/1.1"
            http.sourceIp = "127.0.0.1"
            val requestContext = HttpApiV2ProxyRequestContext()
            requestContext.http = http
            val request = HttpApiV2ProxyRequest()
            request.rawPath = path
            request.requestContext = requestContext
            return request
        }

        /**
         * 복원 뒤 첫 self-invoke·메일 발송이 AWS SDK 초기화를 떠안지 않게 스냅샷 전에 데운다.
         *
         * 서비스 호출은 로컬의 준비된 응답으로 돌고 HTTP 클라이언트만 네트워크를 1번 쓴다. 자격 증명·IAM 권한이
         * 필요 없다(https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/sdk-warmup.html). 실제
         * 클라이언트는 그대로 by lazy라 복원 뒤에 만들어진다. 효과 측정은 docs/PERFORMANCE.md 4.6 참고.
         */
        private fun warmUpAwsSdk() {
            try {
                SdkWarmUp.warmUp(LambdaClient::class.java, SesClient::class.java)
            } catch (e: Exception) {
                logger.warn("AWS SDK warm-up failed: {}", e.message)
            }
        }
    }

    // 요청 하나의 앱 로그를 Lambda RequestId(플랫폼 START·REPORT 줄의 RequestId와 같은 값)로 묶는다. 실행 환경은 다음 호출에
    // 같은 main 스레드를 다시 쓰므로 finally에서 반드시 지운다 - 안 지우면 다음 요청 로그에 이전 ID가 붙는다. @Async 작업은
    // AsyncConfig의 TaskDecorator가 이 값을 넘겨받는다.
    override fun handleRequest(input: InputStream, output: OutputStream, context: Context) {
        MDC.put(REQUEST_ID_MDC_KEY, context.awsRequestId)
        try {
            dispatch(input, output, context)
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY)
        }
    }

    private fun dispatch(input: InputStream, output: OutputStream, context: Context) {
        val event = mapper.readTree(input)

        // self-invoke(AiJobDispatcher·CommentJobDispatcher·FeedJobDispatcher)나 EventBridge 룰이 보낸 내부 작업이면
        // HTTP 라우팅(serverless-java-container)을 거치지 않고 바로 처리한다 — rawPath/requestContext가 없는, Function URL 이벤트와
        // 다른 모양이다. 잡 목록은 docs/BE-ARCHITECTURE.md §1.
        when (event.get("linksphereJob")?.asText()) {
            "ai-analysis" -> {
                handleAiJob(event, output)
                return
            }
            "comment-postprocess" -> {
                handleCommentJob(event, output)
                return
            }
            "feed-crawl" -> {
                handleFeedCrawlJob(output)
                return
            }
            "feed-item" -> {
                handleFeedItemJob(event, output)
                return
            }
            "account-purge" -> {
                handleAccountPurgeJob(output)
                return
            }
            "orphan-image-gc" -> {
                handleOrphanImageGcJob(event, output)
                return
            }
        }

        // Phase 0: CloudFront를 거치지 않고 Function URL을 직접 두드린 요청을 막는다
        // (FunctionUrlOriginGuard 참고, docs/plans/2026-09-28-auth-hardening.md).
        val domainName = event.at("/requestContext/domainName")?.asText()
        val originVerifyHeader = findHeader(event, FunctionUrlOriginGuard.HEADER_NAME)
        if (!FunctionUrlOriginGuard.isAllowed(domainName, originVerifyHeader, System.getenv("ORIGIN_VERIFY_SECRET"))) {
            mapper.writeValue(
                output,
                mapOf(
                    "statusCode" to 403,
                    "body" to mapper.writeValueAsString(mapOf("status" to 403, "code" to "FORBIDDEN", "message" to "Forbidden")),
                ),
            )
            return
        }

        // 이벤트의 경로·쿼리·헤더·쿠키·본문(base64 포함)은 라이브러리가 서블릿 요청으로 옮긴다. 쿼리만 먼저 고친다 —
        // 라이브러리는 쌍을 split("=")로 나눠 Tomcat과 다르게 읽는 경우가 있다(FunctionUrlQuery 참고).
        val request = LambdaContainerHandler.getObjectMapper().treeToValue(event, HttpApiV2ProxyRequest::class.java)
        request.rawQueryString = request.rawQueryString?.let(FunctionUrlQuery::normalize)
        mapper.writeValue(output, FunctionUrlResponse.from(container.proxy(request, context)))
    }

    // Lambda 이벤트의 headers 필드는 대소문자가 원본 그대로 들어오므로(예: X-Origin-Verify vs
    // x-origin-verify) 매번 순회하며 대소문자 구분 없이 찾는다(지금은 X-Origin-Verify만 이 함수로 찾는다).
    private fun findHeader(event: JsonNode, name: String): String? {
        val headers = event.get("headers") ?: return null
        val names = headers.fieldNames()
        var found: String? = null
        while (names.hasNext()) {
            val key = names.next()
            if (key.equals(name, ignoreCase = true)) {
                found = headers.get(key).asText()
            }
        }
        return found
    }

    // AiJobDispatcher가 위임한 AI 분석 작업을 처리한다. 원래 요청의 응답 흐름과 완전히
    // 별도인 실행 환경(비동기 self-invoke)이라 여기서 오래 걸려도 무방하다.
    // InvocationType.EVENT라 이 함수의 리턴값을 읽는 호출자가 없으므로 output 내용 자체는 의미가 없다.
    private fun handleAiJob(event: JsonNode, output: OutputStream) {
        val payload = mapper.treeToValue(event.get("event"), PostCreatedEvent::class.java)
        val postAIService = applicationContext.getBean(PostAIService::class.java)
        try {
            postAIService.processAiJob(payload)
        } catch (e: ObjectOptimisticLockingFailureException) {
            // AI 분석 도중 post가 삭제된 정상적인 레이스 — processAiJob이 로그를 남기고
            // 트랜잭션을 정상 롤백한 뒤 다시 던진 것이다. 여기서 삼켜야 Lambda가 이 호출을
            // 실패로 보고 불필요하게 재시도하지 않는다.
            logger.info("[AI Job] 처리 중 post가 삭제됨 - postId: ${payload.postId}")
        }
        mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
    }

    // CommentJobDispatcher가 위임한 댓글 후처리(알림 발송 + 링크 프리뷰 크롤링)를 처리한다.
    // 삭제 레이스는 CommentPostProcessService.processCommentJob이 조용히 return하므로
    // handleAiJob과 달리 별도 예외 처리가 필요 없다.
    private fun handleCommentJob(event: JsonNode, output: OutputStream) {
        val payload = mapper.treeToValue(event.get("event"), CommentPostProcessEvent::class.java)
        val commentPostProcessService = applicationContext.getBean(CommentPostProcessService::class.java)
        commentPostProcessService.processCommentJob(payload)
        mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
    }

    // EventBridge cron이 직접 호출하는 Stage A 진입점(self-invoke가 아니라 외부 트리거).
    // 피드 소스를 순회해 후보 URL만 모으고, 실제 게시글 생성은 Stage B(feed-item)로 넘긴다.
    private fun handleFeedCrawlJob(output: OutputStream) {
        val feedCrawlService = applicationContext.getBean(FeedCrawlService::class.java)
        feedCrawlService.collectAndDispatch()
        mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
    }

    // FeedJobDispatcher가 위임한 Stage B 처리부. 항목 하나가 실패해도 나머지는 계속 처리하므로
    // (FeedCrawlService.processFeedItemJob 내부의 runCatching) 여기서 별도 예외 처리는 필요 없다.
    private fun handleFeedItemJob(event: JsonNode, output: OutputStream) {
        val payload = mapper.treeToValue(event.get("event"), FeedItemJobEvent::class.java)
        val feedCrawlService = applicationContext.getBean(FeedCrawlService::class.java)
        feedCrawlService.processFeedItemJob(payload)
        mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
    }

    // EventBridge cron(4일마다, link-sphere-feed-crawl 룰을 feed-crawl과 공유)이 직접
    // 호출하는 진입점 - 탈퇴 유예 14일이 지난 계정을 익명화하고, 끝난 레이트리밋 창의 행을 지운다.
    // 회원 한 명이 실패해도 나머지는 계속 처리하므로(AccountPurgeService 내부의 runCatching)
    // 여기서 별도 예외 처리는 필요 없다. 행 삭제가 실패하면 잡 오류로 드러나고, 다시 돌아도 결과가 같다.
    private fun handleAccountPurgeJob(output: OutputStream) {
        val accountPurgeService = applicationContext.getBean(AccountPurgeService::class.java)
        accountPurgeService.purgeExpired()
        applicationContext.getBean(RateLimitService::class.java).purgeExpired()
        mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
    }

    // EventBridge cron(4일마다, link-sphere-feed-crawl 룰을 공유)이 호출하는 고아 이미지 정리.
    // 수동 확인용으로 {"linksphereJob":"orphan-image-gc","dryRun":true}를 보내면 지우지 않고
    // 로그에 요약과 후보 일부만 남긴다(docs/DEPLOY.md 11장).
    private fun handleOrphanImageGcJob(event: JsonNode, output: OutputStream) {
        val dryRun = event.get("dryRun")?.asBoolean() ?: false
        val summary = applicationContext.getBean(OrphanImageGcService::class.java).collect(dryRun)
        if (dryRun) {
            summary.candidates.take(20).forEach { logger.info("[OrphanImageGc] 후보 $it") }
        }
        mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
    }
}
