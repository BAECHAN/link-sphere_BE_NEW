# AWS Lambda SnapStart 배포 가이드

> 마지막 검토: 2026-09-21

## 아키텍처 개요

### 배포 파이프라인
```
GitHub push (main)
  → GitHub Actions
    → shadowJar 빌드 (fat JAR, 모든 의존성 포함)
    → S3 업로드
    → Lambda 코드 업데이트
    → 버전 발행 (SnapStart 스냅샷 생성)
    → 새 버전을 직접 5회 연속 호출 검증 (하나라도 실패하면 여기서 중단)
    → 전부 통과하면 prod alias 자동 승격
```

> ℹ️ **2026-08-13부터 자동 승격(검증 게이트 포함)으로 전환했다.** 이전엔 CI가
> 발행까지만 하고 사람이 직접 연속 호출로 검증한 뒤 수동으로 `update-alias`를
> 실행해야 했는데, 이 수동 단계에 알림이 전혀 없어 2026-08-10~08-12 사이 발행된
> 버전 68~71이 **6일간 미승격 상태로 방치**된 사례가 있었다(CloudTrail로 확인,
> 2026-08-13). "사람이 잊지 않고 매번 승격한다"에 의존하는 구조 자체가 근본
> 문제였다고 판단해, 검증과 승격을 CI 안으로 옮겼다.
>
> **수동 승격을 처음 도입한 이유(2026-07-25 502 장애)는 여전히 유효하고, 그대로
> 자동화 안에 반영돼 있다** — 그 장애는 "복원 후 첫 요청은 성공, 이후 실패" 패턴이라
> 배포 직후 헬스체크 1번으로는 못 잡았다(`docs/PERFORMANCE.md` 5장). 그래서 이번
> 게이트도 **1번이 아니라 5번 연속 호출**해서 전부 성공해야만 승격한다 — "검증 없이
> 자동으로 넘어가지 않는다"는 원칙은 그대로고, 그 검증을 사람이 하던 걸 CI가
> 대신하게 됐을 뿐이다.

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

> **2026-07-25 — Lambda Web Adapter 레이어를 제거했다.**
> 그전까지 함수에는 `LambdaAdapterLayerArm64:24` 레이어가 붙어 있었고, Tomcat이 8080에 떠서
> 실트래픽을 처리하고 있었다(즉 아래 "MockMvc 방식" 서술과 실제가 달랐다).
> 이 레이어는 `AWS_LAMBDA_EXEC_WRAPPER`가 설정되지 않아 익스텐션으로만 떠 있었고,
> `127.0.0.1:8080` 접속에 실패하면 panic하면서 **호출 전체를 502로 실패시켰다**
> (2026-07-25 장애의 직접 원인 — [PERFORMANCE.md](./PERFORMANCE.md) 5장).
> 제거 후 요청은 `LambdaHandler`(MockMvc)가 처리하며, 응답 본문은 제거 전과 동일함을 확인했다
> (헤더명 케이싱만 `vary`→`Vary`로 바뀌는데 HTTP 헤더명은 대소문자를 구분하지 않아 무해).

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
   - warmUp() 실행 — 읽기 전용 엔드포인트로 실제 요청을 흘려보냄
   - SnapStart가 이 상태를 스냅샷으로 저장

2. 요청 수신 (cold start):
   - 스냅샷에서 JVM 복원 (Spring 재시작 없음)
   - handleRequest() 호출
   - MockMvc → DispatcherServlet → 응답

3. 요청 수신 (warm start):
   - 동일 컨테이너 재사용, 즉시 handleRequest() 호출
```

### init 단계에서 워밍업을 실행하는 이유

`companion object init`은 체크포인트 **이전**에 실행되므로, 여기서 수행한 초기화가 전부 스냅샷에 포함된다. 워밍업이 없으면 아래가 모두 복원 이후 첫 요청으로 밀린다.

- `DispatcherServlet` 최초 초기화
- Spring Security 필터 체인 첫 통과
- Hibernate 메타모델·쿼리플랜 캐시 생성
- HikariCP 실제 커넥션 확보
- JIT 미적용 상태(인터프리터) 실행

실측상 restore 자체는 약 0.65초인데 그 뒤 첫 요청이 약 2.9초였던 원인이 이것이다. 자세한 측정·분석은 [PERFORMANCE.md](./PERFORMANCE.md) 참고.

주의사항:
- 워밍업은 **읽기 전용·`permitAll` 엔드포인트만** 사용한다 (부작용 방지)
- 실패해도 부팅은 계속한다 — 배포 시점에 DB가 닿지 않아도 Lambda는 기동되어야 한다
- `DataSourceCracHook.beforeCheckpoint`의 `suspendPool()`은 체크포인트 시점에 호출되므로 init 단계의 DB 워밍업과 충돌하지 않는다. **순서를 바꾸지 말 것**

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
        "lambda:GetFunctionConfiguration",
        "lambda:InvokeFunction"
      ],
      "Resource": "arn:aws:lambda:ap-northeast-1:*:function:link-sphere-api*"
    }
  ]
}
```

> ⚠️ **`lambda:InvokeFunction`은 2026-08-13 자동 승격 게이트 도입 시 추가됐다.**
> 그 이전엔 CI가 발행만 하고 사람이 로컬 자격증명으로 직접 호출했기 때문에 이
> 권한이 필요 없었다. 실제 IAM 정책이 이 문서보다 오래됐다면(이 권한 조회 자체가
> `iam:List*` 권한 없이는 안 되므로 콘솔에서 직접 확인해야 한다) deploy.yml의
> "새 버전 직접 연속 호출 검증" 스텝이 `AccessDenied`로 실패한다 — 이 경우 위
> 정책에 `lambda:InvokeFunction`을 추가해야 한다.

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
  --memory-size 2048 \
  --timeout 120 \
  --architectures arm64 \
  --snap-start ApplyOn=PublishedVersions
```

Lambda 실행 역할 필요 권한: `AWSLambdaBasicExecutionRole`

> **메모리 2048MB인 이유**: Lambda는 메모리에 비례해 vCPU를 준다(1024MB ≈ 0.58 vCPU → 2048MB ≈ 1.15 vCPU). 콜드스타트 첫 요청은 클래스 로딩·JIT 위주의 CPU 바운드라 메모리를 올리면 거의 선형으로 빨라진다. 실사용 메모리는 460MB 수준이므로 메모리 자체가 목적이 아니다. `GB × 초` 과금이라 실행 시간이 줄어 **비용은 거의 중립**이다. ([PERFORMANCE.md](./PERFORMANCE.md))

기존 함수의 메모리를 바꿀 때는 설정 변경 후 새 버전을 발행해야 스냅샷에 반영된다.

```bash
aws lambda update-function-configuration \
  --function-name link-sphere-api --memory-size 2048 --region ap-northeast-1
```

### 4. Lambda 환경변수 설정

Lambda 콘솔 → Configuration → Environment variables:

| 키 | 설명 |
|----|------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://...supabase.com:6543/postgres?prepareThreshold=0` |
| `SPRING_DATASOURCE_USERNAME` | Supabase DB 사용자명 |
| `SPRING_DATASOURCE_PASSWORD` | Supabase DB 비밀번호 |
| `GEMINI_API_KEY` | Gemini API 키 |
| `YOUTUBE_API_KEY` | YouTube Data API v3 키 (영상 설명 추출용, `docs/AI-ASYNC-PROCESSING.md` §5.7 참고) |
| `SUPABASE_BUCKET` | Supabase 스토리지 버킷명 |
| `SUPABASE_KEY` | Supabase service role key |
| `SUPABASE_URL` | `https://<project>.supabase.co` |
| `ORIGIN_VERIFY_SECRET` | CloudFront가 오리진 커스텀 헤더로 붙이는 값(§5-1 참고). 미설정 시 그 검사는 건너뛴다(fail-open) |
| `APP_MAIL_FROM` | SES에서 검증된 발신 주소(§8-1 참고). 비어있으면 메일 발송을 건너뛴다(fail-open) |
| `APP_FRONTEND_URL` | 비밀번호 재설정·이메일 인증 링크에 쓸 프론트엔드 도메인. 커스텀 도메인(`linksphere.click`)이 `application.yml` 기본값에 이미 반영돼 있어(2026-09-29) 지금은 미설정이 정상이다 - 도메인이 다시 바뀌면 이 환경변수로 덮어쓴다 |
| `APP_CORS_ALLOWED_ORIGINS` | CORS 허용 오리진 목록(쉼표 구분, 예: `https://a.com,https://b.com`). `application.yml`의 `app.cors.allowed-origins` 기본값에 이미 도메인이 반영돼 있어 지금은 미설정이 정상이다. **새 도메인을 붙일 때 이 목록에 추가하는 걸 빠뜨리면 브라우저가 로그인 요청 자체를 403 `Invalid CORS request`로 막는다** - 2026-09-29 `linksphere.click` 연결 때 실제로 겪은 장애, 원인 파악까지 시간이 걸렸다(에러가 CORS 문제라고 바로 드러나긴 했으나 어디에 도메인을 등록해야 하는지 소스를 찾아야 했음) |

> Spring Boot는 `SPRING_DATASOURCE_URL` → `spring.datasource.url` 형식으로 환경변수를 자동 바인딩한다.
> `APP_MAIL_FROM`/`APP_FRONTEND_URL`도 같은 규칙으로 각각 `app.mail.from`/`app.frontend.url`에 매핑된다.
> `APP_CORS_ALLOWED_ORIGINS`처럼 `List<String>` 타입 속성(`app.cors.allowed-origins`)은 Spring Boot의
> relaxed binding이 쉼표로 구분된 값 하나를 리스트로 변환해준다 - 배열 인덱스 문법(`_0`, `_1`) 없이도 된다.

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

> **지금 프로덕션은 위 상태(`AuthType: NONE`, 완전 공개) 그대로다.** 아래 5-1은
> 이 상태를 CloudFront OAC + `AWS_IAM`으로 전환하는 **예정된 절차**를 적어둔 것이지,
> 아직 실행되지 않았다 — `docs/plans/2026-09-28-auth-hardening.md` Phase 7의 BE·FE
> 코드가 각각 배포·검증된 뒤에 마지막 단계로 실행한다. 지금 새로 환경을 만드는
> 상황이 아니라면 이 섹션은 그대로 참고만 하고, 실제 `AuthType` 전환 여부는 반드시
> `aws lambda get-function-url-config --function-name link-sphere-api --qualifier prod`로
> 직접 확인한다 — 이 문서의 서술만 믿지 않는다(바로 이 문서가 과거에 한 번 실제
> 상태와 다른 "적용 완료" 표기를 갖고 있었던 사고 사례가 있다, 아래 정정 참고).

#### 5-1. Function URL 직접 호출 완전 차단 (CloudFront OAC) — 절차 정리 (미적용)

전환하면: Function URL이 `AWS_IAM`이고 CloudFront 오리진에 Origin Access
Control(OAC)이 연결돼, CloudFront를 거치지 않고 직접 두드리면 Lambda의 IAM 인가
단계에서 즉시 403을 받게 된다(WAF 경유 없이도 차단 — WAF보다 앞단에서 막히므로
§2 WAF와는 독립적인 방어선이다).

**전환 전 체크리스트** (하나라도 빠지면 전환 즉시 쓰기 요청이 전부 실패한다):

- [ ] FE가 `Authorization` 대신 `X-Access-Token` 헤더로 토큰을 보내는지 확인
  (OAC `SigningBehavior: Always`가 `Authorization`을 CloudFront 자신의 SigV4
  서명으로 덮어쓴다)
- [ ] FE의 모든 POST/PUT/PATCH/DELETE(로그인·글쓰기·댓글·multipart 업로드 포함)가
  본문의 SHA256을 계산해 `x-amz-content-sha256` 헤더로 보내는지 확인 — [AWS 공식
  문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-lambda.html)에
  따르면 CloudFront는 바디를 오리진으로 스트리밍만 할 뿐 이 해시를 대신 계산해주지
  않는다 — Lambda는 서명되지 않은 페이로드(unsigned payload)를 지원하지 않으므로
  헤더가 없는 요청은 이 단계에서 거절된다

절차:

```bash
# 1. CloudFront에 Function URL 호출 권한 부여 (공식 문서: 두 액션 모두 필요 -
#    https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-restricting-access-to-lambda.html)
aws lambda add-permission --function-name link-sphere-api --qualifier prod \
  --statement-id AllowCloudFrontServicePrincipal \
  --action lambda:InvokeFunctionUrl --principal cloudfront.amazonaws.com \
  --source-arn arn:aws:cloudfront::<account-id>:distribution/<distribution-id>

aws lambda add-permission --function-name link-sphere-api --qualifier prod \
  --statement-id AllowCloudFrontServicePrincipalInvokeFunction \
  --action lambda:InvokeFunction --principal cloudfront.amazonaws.com \
  --source-arn arn:aws:cloudfront::<account-id>:distribution/<distribution-id>

# 2. OAC 생성 (SigningBehavior=always 권장값)
aws cloudfront create-origin-access-control --origin-access-control-config \
  Name=link-sphere-api-lambda-oac,SigningProtocol=sigv4,SigningBehavior=always,OriginAccessControlOriginType=lambda

# 3. CloudFront 콘솔(또는 get-distribution-config → OriginAccessControlId 채워서
#    update-distribution) → 이 오리진(Function URL)의 Origin access control에 위에서
#    만든 OAC 연결

# 4. Function URL AuthType 전환
aws lambda update-function-url-config --function-name link-sphere-api \
  --qualifier prod --auth-type AWS_IAM

# 5. 검증 후, 기존 공개 권한이 남아있었다면 제거
aws lambda remove-permission --function-name link-sphere-api --qualifier prod \
  --statement-id FunctionURLAllowPublicAccess
```

**롤백**: `aws lambda update-function-url-config --function-name link-sphere-api
--qualifier prod --auth-type NONE` 한 줄로 즉시 공개 상태로 되돌릴 수 있다(OAC가
오리진에 붙어 있어도 Function URL이 `NONE`이면 서명을 무시하므로 무해) — 자세한
장애 대응은 `docs/LAMBDA-CONFIG-ROLLBACK.md` 참고.

**Phase 0(오리진 시크릿 헤더, `FunctionUrlOriginGuard.kt`) 관련 정정**: 이 문서는
한때 Phase 0가 "적용 완료"라고 적어뒀으나, Phase 7 작업 중 직접 조회해보니
`ORIGIN_VERIFY_SECRET` 환경변수가 실제로는 설정된 적이 없었다(fail-open 상태로
계속 공개돼 있었음, `AuthType: NONE`·CloudFront에 `CustomHeaders` 없음을 CLI로
확인) — 표기 오류였다. OAC(위 5-1) 전환이 완료되면 이 임시 잠금을 대체하게 되므로
그 전환 전까지는 `ORIGIN_VERIFY_SECRET`을 새로 설정할 필요는 없다.
`FunctionUrlOriginGuard.kt` 코드 자체는 유지하되(제거는 별도 판단), 전환 전까지는
지금처럼 fail-open 상태로 남는다는 점을 인지하고 있어야 한다.

#### 5-2. 실제 요청자 IP 전달 (CloudFront-Viewer-Address) — 적용 완료 (2026-09-28)

로그인 실패·가입 레이트리밋(`RateLimitService`, `ClientIpResolver.kt`)이 IP별 버킷을
나누려면 오리진(Lambda)이 실제 요청자 IP를 알아야 한다. `CloudFront-Viewer-Address`는
"AllViewer" 계열 오리진 요청 정책이 자동으로 포함하는 일반 뷰어 헤더가 아니라, 오리진
요청 정책의 헤더 목록에 **명시적으로 추가**해야만 전달되는 CloudFront 전용 헤더다.

```bash
# CloudFront 콘솔 → 이 오리진(Function URL)의 오리진 요청 정책 → 헤더 목록에 추가:
#   CloudFront-Viewer-Address
```

- 값 형식은 `ip:port`(IPv6는 `[::1]:port`) — `ClientIpResolver`가 포트를 잘라낸다.
- 헤더가 아직 없으면(설정 전, 또는 Phase 0 가드를 우회하는 합성 이벤트) `ClientIpResolver`
  가 `null`을 반환하고 `RateLimitService`는 그 축의 레이트리밋만 건너뛴다(5-1과 같은
  fail-open 원칙) — 설정 누락으로 로그인·가입 자체가 막히지 않는다.

### 6. 워밍 핑 (EventBridge 스케줄 룰) — 적용 완료 (2026-07-25)

콜드스타트 발생 비율을 낮추기 위해 5분마다 `prod` alias를 호출해 컨테이너 1개를 살려둔다.

> **타겟은 반드시 `prod` alias여야 한다.** `$LATEST`를 호출하면 SnapStart 스냅샷이 없는
> 별개 컨테이너가 데워질 뿐, 실제 사용자 트래픽이 가는 `prod` 컨테이너는 여전히 콜드다.
> EventBridge 콘솔의 Lambda 타겟 선택 UI는 alias 지정이 노출되지 않을 수 있으므로
> 아래처럼 CLI로 ARN에 `:prod`를 명시해 연결한다.

> **IAM**: 이 셋업에는 `events:PutRule`, `events:PutTargets`, `lambda:AddPermission`이
> 필요하다. `link-sphere-user`에는 원래 없어서 인라인 정책 `ops-warmup-and-diagnostics`로 부여했다.
> **1회성 셋업 권한이므로 규칙 생성 후 회수해도 규칙은 그대로 동작한다** (롤백 시 다시 필요).

```bash
# 5분마다 실행되는 규칙 생성
aws events put-rule \
  --name link-sphere-api-warmup \
  --schedule-expression "rate(5 minutes)" \
  --region ap-northeast-1

# Lambda가 EventBridge 호출을 허용하도록 권한 부여
aws lambda add-permission \
  --function-name link-sphere-api \
  --qualifier prod \
  --statement-id EventBridgeWarmup \
  --action lambda:InvokeFunction \
  --principal events.amazonaws.com \
  --source-arn arn:aws:events:ap-northeast-1:ACCOUNT_ID:rule/link-sphere-api-warmup \
  --region ap-northeast-1

# 대상 지정 — LambdaHandler가 rawPath/requestContext.http.method를 읽으므로
# 합성 이벤트를 constant input으로 넘겨야 정상 라우팅된다 (빈 이벤트면 GET / 로 404)
aws events put-targets \
  --rule link-sphere-api-warmup \
  --region ap-northeast-1 \
  --targets '[{
    "Id": "warmup",
    "Arn": "arn:aws:lambda:ap-northeast-1:ACCOUNT_ID:function:link-sphere-api:prod",
    "Input": "{\"rawPath\":\"/api/actuator/health\",\"requestContext\":{\"http\":{\"method\":\"GET\"}}}"
  }]'
```

- `/actuator/health`는 `management.health.db.enabled: false`라 DB를 건드리지 않아 가볍다
- **한계**: 컨테이너 1개만 유지한다. 동시 요청이 늘면 초과분은 여전히 콜드다
- classic 스케줄 룰은 호출 과금 대상이 아니다

### 7. S3 배포 버킷 수명 주기 — 적용 완료 (2026-07-25)

배포마다 85MB jar가 `deployments/`에 쌓이는데 정리 규칙이 없으면 계속 증가한다(실측: 39개 / 3.31GB). Lambda 컴퓨트보다 큰 비용 항목이 되므로 30일 만료 규칙을 건다.

```bash
aws s3api put-bucket-lifecycle-configuration \
  --bucket link-sphere-lambda-deploy \
  --lifecycle-configuration '{
    "Rules": [{
      "ID": "expire-old-deployments",
      "Status": "Enabled",
      "Filter": { "Prefix": "deployments/" },
      "Expiration": { "Days": 30 }
    }]
  }'
```

- Lambda는 코드를 자체 복사해 보관하므로 S3에서 과거 jar가 지워져도 기존 버전·스냅샷은 정상 동작한다
- 최근 한 달치가 남아 롤백 능력은 유지된다
- 버킷 버전 관리가 켜져 있으면 `NoncurrentVersionExpiration`도 함께 걸어야 실제로 줄어든다

### 8. RSS 피드 자동 수집 (EventBridge 스케줄 룰) — 적용 완료 (2026-09-03, 2026-09-06 주기 축소)

`domain/feed/`가 4일에 1회 RSS/Atom 피드를 수집해 봇 계정 명의로 게시글을 등록한다.
6장 워밍 핑과 동일한 형식의 규칙을 하나 더 만들었다. 적용 순서
(`CHANGELOG.md` `[Unreleased] > Migration` 참고):

1. `sql/create_feed_sources.sql`을 코드 배포 **전에** 먼저 실행 (`members.is_bot` 컬럼 +
   봇 계정 + `feed_sources`/`feed_items` 테이블 + 피드 시딩)
2. 코드가 `prod`로 배포되고 5회 연속 invoke 게이트를 통과한 뒤,
3. 아래 EventBridge 룰을 만들기 **전에** Stage A를 수동으로 한 번 트리거해 검증한다:
   ```bash
   echo '{"linksphereJob":"feed-crawl"}' > /tmp/feed-event.json
   aws lambda invoke --function-name link-sphere-api:prod --log-type Tail \
     --payload fileb:///tmp/feed-event.json /tmp/out.json \
     --query 'LogResult' --output text | base64 -d
   ```
   `feed_items`/`posts` 카운트가 늘었는지, 같은 명령을 한 번 더 실행해도 늘지 않는지
   (멱등성)까지 확인한 뒤에만 다음 단계로 진행한다.

> **타겟은 반드시 `prod` alias여야 한다** — 이유는 6장 워밍 핑과 동일(`$LATEST`엔
> SnapStart 스냅샷이 적용되지 않음).

```bash
# 4일마다 UTC 22:00(KST 07:00) 실행되는 규칙 생성
# day-of-month에 */4를 써서 월 경계(예: 1/29 → 2/1)에서 실제 간격이 3~4일로
# 흔들릴 수 있다 - rate(4 days)는 정확히 4일 간격이지만 실행 시각을 07:00으로
# 고정할 수 없어 이쪽을 택했다 (docs/RSS-FEED-BOT.md §8)
aws events put-rule \
  --name link-sphere-feed-crawl \
  --schedule-expression "cron(0 22 */4 * ? *)" \
  --region ap-northeast-1

# Lambda가 EventBridge 호출을 허용하도록 권한 부여
aws lambda add-permission \
  --function-name link-sphere-api \
  --qualifier prod \
  --statement-id EventBridgeFeedCrawl \
  --action lambda:InvokeFunction \
  --principal events.amazonaws.com \
  --source-arn arn:aws:events:ap-northeast-1:ACCOUNT_ID:rule/link-sphere-feed-crawl \
  --region ap-northeast-1

# 대상 지정 — LambdaHandler가 linksphereJob 필드로 일반 HTTP 이벤트와 구분한다
aws events put-targets \
  --rule link-sphere-feed-crawl \
  --region ap-northeast-1 \
  --targets '[{
    "Id": "feed-crawl",
    "Arn": "arn:aws:lambda:ap-northeast-1:ACCOUNT_ID:function:link-sphere-api:prod",
    "Input": "{\"linksphereJob\":\"feed-crawl\"}"
  }]'
```

- 피드 소스 추가/제거는 재배포 없이 `feed_sources` 테이블에 직접 SQL로 한다
  (관리자 API 없음 — `tools/OrphanImageCleanupRunner.kt`와 같은 이유, 이 코드베이스에
  admin/role 개념이 없어 REST로 노출하면 SSRF 게이트가 된다)
- 결과 확인: `SELECT p.title, p.ai_status FROM posts p JOIN members m ON m.id = p.user_id
  WHERE m.is_bot ORDER BY p.created_at DESC LIMIT 20;`
- `ai_status = FAILED`가 절반 이상이면 Gemini RPM 초과 — `FeedCrawlService`의
  `MAX_ITEMS_PER_SOURCE`(소스당 최대 건수, 기본 1)를 줄여 전체 발행량 자체를
  낮춘다. **chunk 크기(`CHUNK_SIZE`, 기본 5)는 줄이지 말 것** — chunk를 줄이면
  병렬 chunk 수(전체 건수 / `CHUNK_SIZE`)가 오히려 늘어 같은 건수가 더 짧은
  시간에 몰리므로 RPM 초과를 악화시킨다(2026-09-06 정정)
- 발행 주기·상한을 더 낮추려면 `FeedCrawlService`의 `MAX_ITEMS_TOTAL`도 함께
  확인한다 — `MAX_ITEMS_TOTAL`을 소스 수 미만으로 낮추면 소스 순회 순서 셔플과
  맞물려 실행마다 다른 소스가 잘리므로 특정 소스가 영구히 배제되지는 않는다
  (`docs/RSS-FEED-BOT.md` §8 2026-09-06 항목)

### 9. SES 설정 (비밀번호 찾기·이메일 인증 메일 발송) — 적용 완료 (2026-09-28)

`MailService`가 AWS SES로 비밀번호 재설정·이메일 인증 메일을 보낸다. 도메인이 아직
확정 전이라 **개별 이메일 주소 검증(샌드박스 모드)**으로 시작한다 - 프로덕션 전환
(샌드박스 해제, 도메인 통째로 검증)은 도메인 준비 후 별도로 진행한다
(`docs/plans/2026-09-28-auth-hardening.md` "남은 것" 참고).

#### 9-1. 발신 주소 검증

```bash
# 실제 받을 수 있는 주소로(도메인 미확정 상태라 개별 주소 검증만 가능) -
# AWS가 그 주소로 확인 메일을 보내고, 클릭해야 검증이 끝난다
aws ses verify-email-identity --email-address <발신용-이메일> --region ap-northeast-1
```

샌드박스 모드에서는 **수신자 주소도 미리 검증**해야 실제 메일함으로 도착한다
(`aws ses verify-email-identity --email-address <테스트-수신-주소>`) - 검증 안 된
수신자에게 보내면 API 호출 자체는 200으로 끝나지만(MailService는 SES 응답만 보고
성공 여부를 판단하므로) 실제로는 전달되지 않는다. 이 제약은 프로덕션 전환 전까지는
정상이다.

#### 9-2. Lambda 실행 역할에 SES 발송 권한 부여

§1의 GitHub Actions IAM 정책과 달리, 이건 **Lambda 함수 자신의 실행 역할**
(§3에서 만든 `AWSLambdaBasicExecutionRole` 기반 역할)에 인라인 정책으로 추가한다:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["ses:SendEmail", "ses:SendRawEmail"],
      "Resource": "*"
    }
  ]
}
```

```bash
aws iam put-role-policy \
  --role-name <Lambda 실행 역할 이름> \
  --policy-name link-sphere-ses-send \
  --policy-document file://ses-send-policy.json
```

#### 9-3. Lambda 환경변수

§4 표의 `APP_MAIL_FROM`에 9-1에서 검증한 주소를 설정한다. 미설정이면
`MailService`가 발송을 건너뛴다(fail-open) - 로그인·가입 등 나머지 인증 흐름은
이 값과 무관하게 정상 동작한다.

### 10. 탈퇴 유예 만료 계정 정리 (기존 EventBridge 룰에 타겟 추가) — 절차 정리 (미적용)

`AccountPurgeService`가 회원탈퇴 신청 후 14일이 지난 계정을 익명화한다
(`docs/plans/2026-09-29-account-deletion-grace-period.md`). 실제 발생 빈도가
낮을 것으로 예상돼(탈퇴 자체가 드문 액션 + 14일 유예 중 로그인 복구까지
거치고 남는 경우만 대상) 전용 규칙을 새로 만드는 대신 **8장 RSS 피드 수집
규칙(`link-sphere-feed-crawl`, 4일마다 실행)에 타겟을 하나 추가**하는 방식을
택했다 - 새 `put-rule`·`add-permission` 없이 `put-targets` 한 번으로 끝난다.

**왜 권한을 새로 부여하지 않아도 되는가**: Lambda의 EventBridge 호출 허용
권한(`add-permission`)은 타겟이 아니라 **규칙(rule ARN) 단위**로 부여된다 -
"이 규칙에서 오는 호출은 허용"이라는 조건이지 "이 타겟에서 오는 호출"이
아니다. 이미 `EventBridgeFeedCrawl` 권한 문(`source-arn`이 `link-sphere-feed-crawl`
룰 ARN)이 있으므로, 같은 룰에 타겟을 추가하면 그 권한을 그대로 쓴다.
`LambdaHandler`는 어느 룰이 호출했는지는 보지 않고 페이로드의 `linksphereJob`
값으로만 분기하므로(`"feed-crawl"` vs `"account-purge"`) 코드 쪽 영향도 없다.

적용 순서(`CHANGELOG.md` `[Unreleased] > Migration` 참고):

1. `sql/add_member_deletion_requested_at.sql`을 코드 배포 **전에** 먼저 실행
   (`members.deletion_requested_at` 컬럼 + 만료 조회용 부분 인덱스)
2. 코드가 `prod`로 배포되고 5회 연속 invoke 게이트를 통과한 뒤,
3. 아래 타겟을 추가하기 **전에** 수동으로 한 번 트리거해 검증한다:
   ```bash
   echo '{"linksphereJob":"account-purge"}' > /tmp/account-purge-event.json
   aws lambda invoke --function-name link-sphere-api:prod --log-type Tail \
     --payload fileb:///tmp/account-purge-event.json /tmp/out.json \
     --query 'LogResult' --output text | base64 -d
   ```
   로그의 `[AccountPurge] 완료 - purged=N, skipped=N, failed=N` 요약을 확인하고,
   같은 명령을 한 번 더 실행해도 `purged`가 늘지 않는지(멱등성)까지 확인한
   뒤에만 다음 단계로 진행한다. 배포 직후에는 유예 만료 대상이 없어 보통
   `purged=0`이다 - 그 자체로는 실패가 아니다.

```bash
# link-sphere-feed-crawl 룰(8장, 4일마다 UTC 22:00)에 두 번째 타겟을 추가한다.
# put-targets는 Id로 추가·갱신만 하고 기존 타겟(feed-crawl)은 건드리지 않으므로
# 기존 타겟을 다시 나열할 필요가 없다.
aws events put-targets \
  --rule link-sphere-feed-crawl \
  --region ap-northeast-1 \
  --targets '[{
    "Id": "account-purge",
    "Arn": "arn:aws:lambda:ap-northeast-1:ACCOUNT_ID:function:link-sphere-api:prod",
    "Input": "{\"linksphereJob\":\"account-purge\"}"
  }]'
```

- 등록 직후 `aws events list-targets-by-rule --rule link-sphere-feed-crawl
  --region ap-northeast-1`로 타겟이 `feed-crawl`·`account-purge` 둘 다 남아있는지
  확인한다(권한 재사용이 실제로 되는지는 AWS 문서 근거로만 판단했고 이 정확한
  시나리오로 직접 재현 검증은 안 했다 - 다음 실행 시각에 CloudWatch Logs에서
  `[AccountPurge]` 로그가 실제로 찍히는지까지 확인해야 완전히 검증된 것이다).
- **4일마다 실행되므로 최악의 경우 유예가 끝나고 최대 4일이 더 지나야 실제
  익명화된다**(14일 유예 + 최대 4일 = 최대 18일). 늦어져도 유예가 끝난 계정은
  이미 "탈퇴한 사용자"로 숨겨져 있어 데이터 손상은 없다 - 실제 익명화·개인
  데이터 삭제만 미뤄진다.
- 회원별로 별도 트랜잭션이라(`AccountPurgeService.purgeExpired`) 한 명이 실패해도
  나머지는 그대로 처리된다 - 실패 건은 다음 실행에 재시도된다(claim 조건이
  다시 통과하므로 별도 재처리 코드가 필요 없다).
- 결과 확인: `SELECT count(*) FROM members WHERE deletion_requested_at IS NOT NULL
  AND deleted_at IS NULL AND deletion_requested_at < now() - interval '18 days';`가
  0에 가깝게 유지되는지 주기적으로 확인한다(0이 아니면 타겟이 안 돌고 있거나
  실패가 누적되는 신호).

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
| 5. shadowJar 빌드 | `./gradlew ktlintCheck test shadowJar` → 스타일 검사·테스트 통과 후 모든 의존성 포함된 fat JAR |
| 6. JAR 검증 | 파일 존재 및 `LambdaHandler` 클래스 포함 여부 확인 |
| 7. AWS 자격증명 | GitHub Secrets로 AWS 인증 |
| 8. S3 업로드 | `deployments/YYYYMMDD-HHMMSS.jar` 키로 업로드 |
| 9. 코드 업데이트 | Lambda가 새 JAR를 참조하도록 변경 |
| 10. 업데이트 대기 | `function-updated` waiter로 완료 확인 |
| 11. 버전 발행 | `publish-version` → SnapStart 스냅샷 생성 트리거 |
| 12. SnapStart 대기 | `published-version-active` waiter (1~5분, 스냅샷 완성까지) |
| 13. 연속 호출 검증 | 방금 발행한 **버전 번호를 직접 지정**해 `GET /post`를 5회 연속 호출. 하나라도 `statusCode != 200`이면 워크플로우 실패, 아래 승격 스텝은 실행 안 됨(`prod`는 이전 버전 유지) |
| 14. prod alias 승격 | 13번을 전부 통과했을 때만 `update-alias`로 `prod`를 이 버전으로 이동 |
| 15. Function URL 출력 | 승격된(=현재 서빙 중인) `prod` URL을 로그에 표시 |

**CI가 발행부터 승격까지 전부 자동으로 처리한다** — 사람이 매번 기억해서 승격할
필요가 없다. 13번 검증 게이트가 2026-07-25 502 장애의 교훈("복원 후 첫 요청은
성공, 이후 실패" 패턴은 단발 확인으로 못 잡는다)을 그대로 반영한다 — 1번이 아니라
5번 연속 성공해야 승격되므로, 검증 없이 자동으로 넘어가는 게 아니라 **검증을 CI가
대신 수행**하는 것이다.

### 수동 개입이 필요한 경우 (예외 상황)

정상 배포는 위 파이프라인이 전부 처리하므로 아래 명령은 **롤백이나 CI 우회가
필요한 예외 상황에만** 쓴다.

```bash
# 특정 버전으로 직접 호출 검증 (단발 확인 금지 — docs/LAMBDA-CONFIG-ROLLBACK.md 참고)
aws lambda invoke --function-name link-sphere-api:<VERSION> --payload fileb://event.json /tmp/out.json
# 응답이 안정적으로 나올 때까지 3~5회 반복

# 검증 통과 후 승격 (예: 문제 있는 최신 버전에서 이전 정상 버전으로 되돌릴 때)
aws lambda update-alias --function-name link-sphere-api --name prod --function-version <VERSION>

# 승격 후에도 CloudFront 경유로 다시 연속 호출해 확인
```

`.github/workflows/prod-alias-drift-check.yml`이 6시간마다 `prod`와 최신 발행
버전을 비교해 2시간 이상 벌어지면 GitHub Issue(`deploy-drift` 라벨)를 연다.
**자동 승격 체제에서 이 Issue가 뜬다는 건 정상 배포 흐름이 아니라, 대부분 13번
검증 게이트가 실패해서 승격이 안 된 상황이라는 뜻이다** — 먼저 해당 배포의 Actions
로그에서 어느 호출이 실패했는지 확인하고, 원인을 고친 뒤 재배포(또는 위 수동
명령으로 개입)한다.

---

## 배포 트리거 조건

`main` 브랜치 push 시, 아래 경로에 변경이 있을 때만 실행:
- `src/**`
- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/**`
- `.github/workflows/deploy.yml`

### GitHub Actions 수동 재실행

push 트리거 외 `workflow_dispatch`도 열려 있다 — GitHub Actions push 이벤트
전달 장애(2026-08-06, 실제로 fbd32eb 커밋의 배포를 놓친 사례 있음) 등으로
자동 트리거가 안 될 때 Actions 탭 또는 아래 명령으로 재실행한다.

```bash
gh workflow run deploy.yml --repo BAECHAN/link-sphere_BE_NEW --ref main
```

---

## 배포 후 검증

```bash
# health check
curl https://<function-url>/actuator/health
# 응답: {"status":"UP"}
# (OAC 전환 후에는 이 직접 호출이 403을 반환하는 게 정상이다 — §5-1 참고.
#  전환 후 헬스체크는 CloudFront 경유(`https://<cloudfront-domain>/api/actuator/health`)로 한다)

# SnapStart 동작 확인 (CloudWatch Logs)
# RESTORE_START / RESTORE_END 로그가 보이면 SnapStart 정상 동작

# CloudFront를 거쳐도 403/404가 그대로 오는지 확인 (존재하지 않는 ID로)
# 아래처럼 200 + server: AmazonS3 가 나오면 CloudFront가 에러를 index.html로 가리고 있는 것 —
# CustomErrorResponses에 403/404가 다시 들어갔는지 확인할 것 (FE docs/SYSTEM-ARCHITECTURE.md 참고)
curl -sD - -o /dev/null https://<cloudfront-domain>/api/post/00000000-0000-0000-0000-000000000000
# 기대: HTTP/2 404, body에 POST_NOT_FOUND, server: AmazonS3 헤더 없음
```

403 응답 body가 JSON(`{"code":"..."}`)이 아니라 HTML(`Request blocked.` 등)이면 CloudFront WAF가
Lambda에 닿기도 전에 차단한 것이다 — 앱 예외 처리와 무관하다. 원인·현재 룰 구성·재적용 절차는
FE `docs/DEPLOY.md`의 "CloudFront WAF (수동 관리)" 절 참고.

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
