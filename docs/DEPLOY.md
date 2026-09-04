# AWS Lambda SnapStart 배포 가이드

> 마지막 검토: 2026-08-07

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
  --timeout 30 \
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

### 8. RSS 피드 자동 수집 (EventBridge 스케줄 룰) — 적용 완료 (2026-09-03)

`domain/feed/`가 매일 1회 RSS/Atom 피드를 수집해 봇 계정 명의로 게시글을 등록한다.
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
# 매일 UTC 22:00(KST 07:00) 실행되는 규칙 생성
aws events put-rule \
  --name link-sphere-feed-crawl \
  --schedule-expression "cron(0 22 * * ? *)" \
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
- `ai_status = FAILED`가 절반 이상이면 Gemini RPM 초과 — `FeedCrawlService`의 chunk 크기(5)를
  줄인다

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

# SnapStart 동작 확인 (CloudWatch Logs)
# RESTORE_START / RESTORE_END 로그가 보이면 SnapStart 정상 동작

# CloudFront를 거쳐도 403/404가 그대로 오는지 확인 (존재하지 않는 ID로)
# 아래처럼 200 + server: AmazonS3 가 나오면 CloudFront가 에러를 index.html로 가리고 있는 것 —
# CustomErrorResponses에 403/404가 다시 들어갔는지 확인할 것 (FE docs/SYSTEM-ARCHITECTURE.md 참고)
curl -sD - -o /dev/null https://<cloudfront-domain>/api/post/00000000-0000-0000-0000-000000000000
# 기대: HTTP/2 404, body에 POST_NOT_FOUND, server: AmazonS3 헤더 없음
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
