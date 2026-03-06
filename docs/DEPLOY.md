# AWS Lambda SnapStart 배포 가이드

## 아키텍처 개요

### 배포 파이프라인
```
GitHub push (main)
  → GitHub Actions
    → shadowJar 빌드 (fat JAR, 모든 의존성 포함)
    → S3 업로드
    → Lambda 코드 업데이트
    → 버전 발행 (SnapStart 스냅샷 생성)
    → prod alias 업데이트
```

### Lambda 실행 구조
```
API 요청
  → Lambda Function URL
    → LambdaHandler.handleRequest()
      → MockMvc.perform()
        → Spring DispatcherServlet (Tomcat 소켓 없음)
          → 응답
```

---

## 핵심 동작 원리

### MockMvc 방식을 사용하는 이유

SnapStart는 Lambda init phase를 스냅샷으로 저장해 cold start를 단축한다. 그런데 일반적인 Spring Boot + Tomcat 방식은 두 가지 문제가 있다.

1. **CRaC 체크포인트 실패**: Tomcat이 8080 소켓을 열고 있는 상태에서 SnapStart 체크포인트를 시도하면 열린 소켓이 있어서 `State:Failed`가 된다.
2. **restore 후 rebind 실패**: 체크포인트를 통과하더라도 복원 후 Tomcat이 8080 포트에 재바인딩하지 못해 요청을 처리할 수 없다.

**해결책**: Tomcat을 아예 사용하지 않는다. `MockMvc`로 `DispatcherServlet`을 직접 호출하면 소켓이 전혀 열리지 않으므로 CRaC 체크포인트가 성공하고, 복원 후에도 바인딩 문제가 없다.

### SnapStart 동작 흐름

```
1. Init phase:
   - LambdaHandler.companion.init { } 실행
   - Spring Boot 시작 (MockMvc 초기화 포함)
   - SnapStart가 이 상태를 스냅샷으로 저장

2. 요청 수신 (cold start):
   - 스냅샷에서 JVM 복원 (Spring 재시작 없음)
   - handleRequest() 호출
   - MockMvc → DispatcherServlet → 응답

3. 요청 수신 (warm start):
   - 동일 컨테이너 재사용, 즉시 handleRequest() 호출
```

### Shadow JAR에서 spring.factories를 append하는 이유

Shadow JAR 플러그인의 `mergeServiceFiles()`는 `META-INF/services/**` 파일만 병합한다. Spring Boot의 `ApplicationContextFactory` 구현체들은 `META-INF/spring.factories`에 등록되어 있는데, 이 파일은 `mergeServiceFiles()` 대상이 아니다.

이 파일이 누락되면:
- `DefaultApplicationContextFactory.getFromSpringFactories()` → 구현체 없음
- 폴백: `AnnotationConfigApplicationContext` 생성 (웹 컨텍스트가 아님)
- `MockMvcBuilders.webAppContextSetup(ctx as WebApplicationContext)` → **ClassCastException**

따라서 `append("META-INF/spring.factories")`를 명시적으로 추가해야 한다. 추가로, `LambdaHandler`에서 `createApplicationContext()`를 오버라이드해 spring.factories 조회 자체를 우회하는 이중 방어도 적용되어 있다.

---

## AWS 초기 설정 (최초 1회)

### 1. IAM 사용자 생성 (GitHub Actions용)

최소 권한 정책:
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject"],
      "Resource": "arn:aws:s3:::link-sphere-lambda-deploy/*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "lambda:UpdateFunctionCode",
        "lambda:PublishVersion",
        "lambda:CreateAlias",
        "lambda:UpdateAlias",
        "lambda:GetAlias",
        "lambda:GetFunction",
        "lambda:GetFunctionConfiguration"
      ],
      "Resource": "arn:aws:lambda:ap-northeast-1:*:function:link-sphere-api*"
    }
  ]
}
```

### 2. S3 버킷 생성

```bash
aws s3 mb s3://link-sphere-lambda-deploy --region ap-northeast-1
```
- 퍼블릭 액세스: 모두 차단
- 버전 관리: 활성화 권장

### 3. Lambda 함수 생성

```bash
aws lambda create-function \
  --function-name link-sphere-api \
  --runtime java17 \
  --handler com.example.linksphere.LambdaHandler \
  --role arn:aws:iam::ACCOUNT_ID:role/lambda-execution-role \
  --code S3Bucket=link-sphere-lambda-deploy,S3Key=initial.jar \
  --memory-size 1024 \
  --timeout 30 \
  --architectures arm64 \
  --snap-start ApplyOn=PublishedVersions
```

Lambda 실행 역할 필요 권한: `AWSLambdaBasicExecutionRole`

### 4. Lambda 환경변수 설정

Lambda 콘솔 → Configuration → Environment variables:

| 키 | 설명 |
|----|------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://...supabase.com:6543/postgres?prepareThreshold=0` |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB 비밀번호 |
| `GEMINI_API_KEY` | Gemini API 키 |
| `SUPABASE_BUCKET` | Supabase 스토리지 버킷명 |
| `SUPABASE_KEY` | Supabase service role key |
| `SUPABASE_URL` | `https://<project>.supabase.co` |
| `JWT_SECRET` | JWT 서명 키 (최소 32자) |

> Spring Boot는 `SPRING_DATASOURCE_URL` → `spring.datasource.url` 형식으로 환경변수를 자동 바인딩한다.

### 5. Function URL 생성

```bash
# prod alias에 Function URL 생성
aws lambda create-function-url-config \
  --function-name link-sphere-api \
  --qualifier prod \
  --auth-type NONE

# 퍼블릭 접근 허용
aws lambda add-permission \
  --function-name link-sphere-api \
  --qualifier prod \
  --statement-id FunctionURLAllowPublicAccess \
  --action lambda:InvokeFunctionUrl \
  --principal "*" \
  --function-url-auth-type NONE
```

---

## GitHub 설정

### Secrets (암호화 저장)

| Secret 이름 | 설명 |
|-------------|------|
| `AWS_ACCESS_KEY_ID` | IAM 사용자 액세스 키 |
| `AWS_SECRET_ACCESS_KEY` | IAM 사용자 시크릿 키 |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase 서비스 계정 JSON 전문 |

### Variables (평문 저장)

| Variable 이름 | 예시 값 |
|---------------|---------|
| `AWS_REGION` | `ap-northeast-1` |
| `AWS_S3_BUCKET` | `link-sphere-lambda-deploy` |
| `LAMBDA_FUNCTION_NAME` | `link-sphere-api` |

---

## 배포 흐름 (deploy.yml 단계별)

| 단계 | 설명 |
|------|------|
| 1. Checkout | 소스 체크아웃 |
| 2. JDK 17 | Amazon Corretto 설치 (Lambda 런타임과 동일 계열) |
| 3. Gradle 캐시 | 의존성 캐시로 빌드 시간 단축 |
| 4. Firebase JSON | GitHub Secret → `src/main/resources/firebase-service-account.json` (classpath 포함) |
| 5. shadowJar 빌드 | `./gradlew shadowJar` → 모든 의존성 포함된 fat JAR |
| 6. JAR 검증 | 파일 존재 및 `LambdaHandler` 클래스 포함 여부 확인 |
| 7. AWS 자격증명 | GitHub Secrets로 AWS 인증 |
| 8. S3 업로드 | `deployments/YYYYMMDD-HHMMSS.jar` 키로 업로드 |
| 9. 코드 업데이트 | Lambda가 새 JAR를 참조하도록 변경 |
| 10. 업데이트 대기 | `function-updated` waiter로 완료 확인 |
| 11. 버전 발행 | `publish-version` → SnapStart 스냅샷 생성 트리거 |
| 12. SnapStart 대기 | `published-version-active` waiter (1~5분, 스냅샷 완성까지) |
| 13. alias 업데이트 | `prod` alias를 새 버전으로 교체 |
| 14. URL 출력 | 배포된 Function URL 확인 |

---

## 배포 트리거 조건

`main` 브랜치 push 시, 아래 경로에 변경이 있을 때만 실행:
- `src/**`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/**`
- `.github/workflows/deploy.yml`

---

## 배포 후 검증

```bash
# health check
curl https://<function-url>/actuator/health
# 응답: {"status":"UP"}

# SnapStart 동작 확인 (CloudWatch Logs)
# RESTORE_START / RESTORE_END 로그가 보이면 SnapStart 정상 동작
```

---

## 로컬 개발 환경

로컬에서는 `src/main/resources/application-secret.yml` 파일로 설정값을 관리한다 (gitignore).

```yaml
# application-secret.yml 예시 구조
spring:
  datasource:
    url: jdbc:postgresql://...
    username: ...
    password: ...
jwt:
  secret: ...
gemini:
  api:
    key: ...
```

Lambda에서는 이 파일 없이 환경변수로 동일한 값을 주입한다. Spring Boot가 `SPRING_DATASOURCE_URL` 형식의 환경변수를 자동으로 `spring.datasource.url`에 바인딩한다.

---

## 시행착오 기록

### 1. Docker 방식 시도 → 폐기

초기에는 `Dockerfile` + ECR + Lambda 컨테이너 이미지 방식을 시도했다. 문제 없이 동작하지만 이미지 빌드 시간이 길고, SnapStart는 zip 배포 방식에서만 지원된다. Shadow JAR 직접 배포 방식으로 전환.

### 2. Tomcat 소켓 문제 (SnapStart State:Failed)

일반 Spring Boot 내장 Tomcat은 8080 포트 소켓을 유지한다. SnapStart의 CRaC 체크포인트는 열린 소켓이 있으면 `State:Failed`를 반환한다. HikariCP도 DB 연결 소켓을 유지하므로 동일한 문제가 발생한다.

**해결**: `org.crac:crac` 의존성 추가. Spring Boot 3.x가 CRaC를 인식해 체크포인트 전 Tomcat/HikariCP 소켓을 자동으로 닫고, 복원 후 재연결한다.

### 3. Tomcat restore 후 rebind 실패

crac로 체크포인트는 통과했지만, 복원 후 Tomcat이 8080 포트에 다시 바인딩하지 못하는 문제가 발생했다. Lambda 환경의 네트워크 제약으로 인한 것으로 추정.

**해결**: Tomcat 자체를 사용하지 않는 MockMvc 방식으로 전환. `MockMvc`로 `DispatcherServlet`을 직접 호출하면 소켓이 전혀 필요 없다.

### 4. WebApplicationType.NONE 감지 문제

Lambda 런타임의 thread context classloader(시스템 클래스로더)에는 shadow JAR 내부의 `jakarta.servlet.Servlet`이 없다. `WebApplicationType.deduceFromClasspath()`가 `null` classloader로 클래스를 탐색하면 Servlet을 찾지 못해 `NONE`으로 판단, 서블릿 컨텍스트 없이 Spring이 시작되었다.

**해결**:
```kotlin
Thread.currentThread().contextClassLoader = LambdaHandler::class.java.classLoader
```
shadow JAR의 classloader로 교체해 Servlet 클래스를 찾을 수 있게 함.

### 5. spring.factories 미병합 → ClassCastException (핵심 문제)

Shadow JAR 빌드 후 Lambda 배포 시 다음 에러 반복:
```
ClassCastException: AnnotationConfigApplicationContext cannot be cast to WebApplicationContext
```

**원인**: Shadow JAR의 `mergeServiceFiles()`는 `META-INF/services/**`만 병합. Spring Boot의 `ApplicationContextFactory` 구현체 목록이 담긴 `META-INF/spring.factories`는 병합되지 않아 누락. 결과적으로 Spring이 `AnnotationConfigApplicationContext`(비웹)로 폴백.

**해결 1 — 빌드 레벨**:
```kotlin
// build.gradle.kts
append("META-INF/spring.factories")
```

**해결 2 — 코드 레벨 (이중 방어)**:
```kotlin
// LambdaHandler.kt - spring.factories 조회 자체를 우회
val app = object : SpringApplication(LinkSphereBeApplication::class.java) {
    override fun createApplicationContext(): ConfigurableApplicationContext =
        AnnotationConfigServletWebServerApplicationContext()
}
```

### 6. SpringApplication.applicationContextFactory setter 접근 불가

Spring Boot 3.5.x에서 `applicationContextFactory` 필드가 `private`으로 변경되어 다음 코드가 컴파일 에러 발생:
```kotlin
app.applicationContextFactory = ApplicationContextFactory.ofContextClass(...)
// Error: Cannot access 'applicationContextFactory': it is private in 'SpringApplication'
```

**해결**: `createApplicationContext()`를 익명 서브클래스로 오버라이드.

### 7. MockMvc에 Spring Security 필터 미적용 → 500 + CORS 에러

`MockMvcBuilders.webAppContextSetup(ctx).build()`만으로는 `FilterChainProxy`(Spring Security 전체 필터 체인)가 MockMvc에 자동 포함되지 않는다.

**증상**:
- `GET /auth/account` → `NullPointerException: Parameter specified as non-null is null: method AuthController.getAccount, parameter principal` → 500
- 배포 환경(CloudFront → Lambda URL)에서 CORS 헤더 미설정 → 브라우저 CORS 에러

**원인 분석**: CloudWatch 로그에서 MockMvc 요청(메인 스레드)에 `JwtAuthenticationFilter` 로그가 없음을 확인. `FilterChainProxy`가 없으니 `CorsFilter`, `JwtAuthenticationFilter`, `SecurityContextHolderAwareRequestFilter` 모두 미실행 → `request.getUserPrincipal()` = null → Kotlin non-null 파라미터 NPE.

**해결**:
```kotlin
val securityFilter = ctx.getBean("springSecurityFilterChain") as jakarta.servlet.Filter
val builder = MockMvcBuilders.webAppContextSetup(ctx as WebApplicationContext)
builder.addFilters<DefaultMockMvcBuilder>(securityFilter)
mockMvc = builder.build()
```

> `spring-security-test`의 `springSecurity()` configurer를 쓰면 더 간결하지만, 해당 라이브러리가 `testImplementation`이므로 `springSecurityFilterChain` 빈을 직접 가져와 `addFilters`로 등록하는 방식 사용.

### 8. SnapStart 복원 후 HikariCP 연결 문제

**증상**: SnapStart 복원 직후 DB 쿼리 실패. CloudWatch 로그에서 두 가지 경고 확인:
```
HikariPool-1 - Failed to validate connection (This connection has been closed.)
HikariDataSource is not configured to allow pool suspension.
HikariPool-1 - Thread starvation or clock leap detected (housekeeper delta=1m11s...)
```

**원인**: 스냅샷 저장 시점의 DB 연결이 복원 후 죽어 있음(PgBouncer가 유휴 연결 종료). `keepalive-time`만으로는 이미 죽은 연결을 복원 직후 즉시 감지하지 못함.

**해결**:
```yaml
hikari:
  keepalive-time: 30000       # 유휴 중 연결 끊김 사전 예방
  connection-test-query: SELECT 1  # pool에서 꺼낼 때 즉시 검증 → 죽은 연결 교체
  allow-pool-suspension: true # 체크포인트 전 pool 중단 허용 (경고 제거)
