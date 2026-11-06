# Link-Sphere BE — 성능 (Lambda 콜드스타트)

> 마지막 검토: 2026-11-06 (§3·§4.1 워밍업이 스냅샷에 담는 것과 3회의 근거만 다시 봄. 2026-11-05에는 §4.6 AWS SDK 첫 호출 데우기와 §7 측정 방법 추가)

이 문서는 **"왜 느렸고, 무엇을 근거로 무엇을 바꿨는지"** 를 남긴다.
배포 절차는 [DEPLOY.md](./DEPLOY.md), 무엇이 바뀌었는지는 [CHANGELOG.md](../CHANGELOG.md)를 참고.

---

## 1. 문제

첫 화면 로딩이 3~4초 걸린다는 제보에서 출발했다. 측정해 보니 원인은 **Lambda 콜드스타트**였다.

---

## 2. 측정 기준선 (개선 전)

- **측정일**: 2026-07-25
- **대상**: `link-sphere-api:prod` (ap-northeast-1, java17, arm64, 1024MB, SnapStart On)
- **표본**: 최근 48시간 CloudWatch `REPORT` 로그 **324건**

| 구간 | p50 | p90 | max |
| ---- | --- | --- | --- |
| **콜드 총합** (restore + 첫 요청) | **3,523ms** | **7,099ms** | 9,914ms |
| ├ SnapStart restore | 659ms | 847ms | 926ms |
| └ restore 이후 첫 요청 | **2,906ms** | 6,338ms | 9,203ms |
| **웜 요청** | 148ms | 400ms | — |

- **콜드 비율 22%** (72 / 324)
- `Max Memory Used` 460MB / 1024MB → 메모리는 남는다

---

## 3. 원인 분석

**SnapStart 자체는 정상 동작했다.** restore는 659ms로 문제가 아니었다.
진짜 병목은 **restore 이후 첫 요청 2.9초**다.

이 기준선을 잰 당시 실제 요청은 Tomcat이 처리했다(5장). 이 절은 처음에 MockMvc 경로로 잘못 알고 "아래 초기화가 전부 스냅샷
바깥에 남았다"고 썼고, 2026-11-06에 항목별로 바로잡았다.

| 첫 요청 비용으로 꼽은 항목 | 지금 구조에서 스냅샷에 들어가나 | 근거 |
| --- | --- | --- |
| `DispatcherServlet` 초기화(`Initializing Servlet 'dispatcherServlet'`는 Tomcat 경로의 로그) | 워밍업과 무관하게 들어간다. init의 `builder.build()`가 초기화한다 | spring-test 6.2.19 `MockMvcBuilderSupport.java:78` |
| Hibernate 메타모델 | 워밍업과 무관하게 들어간다. 컨텍스트 기동 때 만들어진다 | hibernate-core 6.6.53 `SessionFactoryImpl.java:297-373` |
| Spring Security 필터 체인 첫 통과, 쿼리 플랜, 처음 쓰는 클래스 로딩 | 워밍업이 지나간 경로만 들어간다 | 4.1 |
| HikariCP 커넥션 확보(Supabase 풀러까지 왕복) | **들어가지 않는다.** 체크포인트 직전에 닫고 복원 뒤 다시 맺는다 | spring-boot 3.5.16 `HikariCheckpointRestoreLifecycle.java:125-145`, 운영 로그 `Evicting Hikari connections` |
| 인터프리터 실행(JIT 미적용) | 확인하지 못했다 | 4.1의 3회 근거 |

여기에 1024MB(≈0.58 vCPU)라 CPU 바운드인 클래스 로딩·JIT이 더 느렸다.

---

## 4. 적용한 최적화

### 4.1 SnapStart 스냅샷에 워밍업 굽기 — 적용 중 (한 번 실패 후 재적용)

`companion object init`은 체크포인트 **이전**에 실행되므로, 여기서 실제 요청을 흘려보내면
처음 쓸 때 하는 준비가 스냅샷에 포함된다. `LambdaHandler.warmUp()`이 이를 수행한다. 버전을 발행할 때 init에서 돌고,
복원 때는 다시 돌지 않아 사용자 요청을 늦추지 않는다. 발행 뒤에도 init이 다시 돌 수 있다 — 버전 197은 2026-11-06 UTC 11:41과
11:57에 `Evicting Hikari connections`가 찍혔다(직접 확인, CloudWatch 로그). [AWS SnapStart 문서](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html)는
소프트웨어 업데이트를 적용할 때 init을 다시 돌린다고 쓰지만(번역, 요약), 이번 재실행이 그 경우인지는 확인하지 못했다.

- `GET /actuator/health` → 요청 처리 공통 경로(Security 필터 체인, `DispatcherServlet` 요청 처리, Jackson)와 DB 연결 확인
- `GET /common/category-option` → 추가로 Controller부터 Repository까지, Hibernate 쿼리 처리
- 두 경로를 3회씩, 총 6번 호출한다. 컨트롤러 API 중 지나가는 것은 `GET /common/category-option`뿐이다. 글 목록·쓰기와
  토큰이 있을 때의 세션 조회는 복원 뒤 첫 호출 때 준비한다
- **3회는 측정으로 정한 값이 아니다.** 도입 때(`ee0cdae`)의 코드 주석은 JIT를 인터프리터 밖으로 민다는 이유를 댔지만, 6장은 워밍업
  유무만 비교했고 1회와 3회를 비교한 기록은 없다. C1 컴파일 기준은 호출 200회다(직접 확인, 로컬 Corretto 17.0.14에서
  `java -XX:+PrintFlagsFinal -version`의 `Tier3InvocationThreshold`. Lambda 런타임의 값은 확인하지 못했다). 요청당 한 번 불리는
  메서드는 3회로 닿지 않으므로 효과 대부분은 첫 1회의 준비에서 나올 것으로 보지만, 측정하지 않은 추정이다
- 응답이 2xx가 아니면 WARN — 조용한 실패(경로 오타 등)를 막는다

> ⚠️ **2026-07-25에 첫 시도가 프로덕션 502 장애를 일으켰다.** 원인은 워밍업 자체가 아니라
> 당시 붙어 있던 Lambda Web Adapter 레이어였다. 5장의 사후 분석을 반드시 읽을 것.
> **LWA 레이어가 없는 상태에서만 이 워밍업이 성립한다** — 레이어를 되돌리면 다시 깨진다.

제약:
- 읽기 전용·`permitAll` 엔드포인트만 사용한다 (부작용 방지)
- 실패해도 부팅은 계속한다 — 배포 시점에 DB가 닿지 않아도 Lambda는 기동되어야 한다
- HikariCP 풀은 Spring Boot의 `HikariCheckpointRestoreLifecycle`이 체크포인트 직전에 멈추고
  커넥션을 닫으므로(복원 때 다시 연다) init 단계의 DB 워밍업과 충돌하지 않는다. **순서를 바꾸지 말 것**
  (2026-10-16 이전에는 자체 훅 `DataSourceCracHook`이 같은 일을 겹쳐 하다 삭제됐다 — DEPLOY.md 시행착오 8)

### 4.2 메모리 1024 → 2048MB — 적용 중

Lambda는 메모리에 비례해 vCPU를 준다(1024MB ≈ 0.58 vCPU → 2048MB ≈ 1.15 vCPU).
첫 요청은 CPU 바운드라 거의 선형으로 줄어들 것으로 기대했다. 메모리 자체가 목적이
아니다(사용량 460MB).

이 변경은 장애와 **무관함이 입증됐고**(5장 v41 실험) 비용도 사실상 0이라 적용했다.
다만 **성능 기여도는 확정하지 못했다** — 측정 편차가 커서 판단이 불가능했다(6장).

### 4.3 EventBridge 워밍 핑 (5분)

콜드 비율 22%를 구조적으로 낮춘다. 다만 **컨테이너 1개만** 유지하므로 동시 요청 초과분은
여전히 콜드다 — 그래서 4.1이 여전히 핵심이다. 생성 커맨드는 [DEPLOY.md](./DEPLOY.md) 참고.

타겟은 반드시 `prod` alias여야 한다. `$LATEST`는 SnapStart 스냅샷이 없는 별개 컨테이너라
데워봐야 실제 사용자 트래픽에 도움이 되지 않는다.

### 4.4 기동 설정

- `spring.jpa.open-in-view: false` — 요청당 커넥션을 뷰 렌더까지 붙들지 않음 (기본값 true + 기동 경고)
- `spring.jmx.enabled: false` — MBean 등록 단계 생략

### 4.5 만료 토큰 로그 등급 (역사적 기록 — 2026-09-28 JWT 폐지로 이 코드 자체가 삭제됨)

> ⚠️ 아래는 이 최적화를 적용할 당시(2026-08)의 기록이다. `JwtAuthenticationFilter`·
> `JwtTokenProvider`는 PR #42(`d9afef9`, 2026-09-28)의 JWT 폐지로 `SessionAuthenticationFilter`·
> `MemberSessionService` 기반 서버 관리 세션으로 완전히 대체되며 삭제됐다 — "현재
> 적용된 최적화"가 아니라 더 이상 존재하지 않는 코드에 대한 과거 기록이다.

`JwtAuthenticationFilter` / `JwtTokenProvider`가 만료 토큰마다 `ExpiredJwtException`
**전체 스택트레이스**를 ERROR로 남기고 있었다. 만료는 FE가 refresh로 복구하는 정상 흐름이라
스택 생성 + 로그 쓰기가 낭비였다. WARN 한 줄로 낮췄다(위변조·서명 불일치는 ERROR 유지).

### 4.6 AWS SDK 첫 호출을 스냅샷 전에 데우기 — 적용 중, 효과 판정 전 (2026-11-05)

복원된 인스턴스가 처음 작업을 발행할 때 `LambdaSelfInvoker`의 `by lazy` 클라이언트가 만들어진다. 이 호출은 커밋 뒤 요청
스레드에서 동기로 돌아 댓글·글 등록 응답을 늦춘다.

직접 측정(2026-11-05, Logs Insights 최근 30일, 쿼리는 7장). 위임 로그에서 `작업 발행` 로그까지의 간격이고, 위임 로그를 찍는
AI·댓글 발행만 잡힌다. 피드 발행은 빠져 있다.

| 구분 | 인스턴스 첫 발행 | 이후 발행 |
| --- | --- | --- |
| 전체 | n=80, p50 1,713ms, p90 2,007ms, 최대 2,364ms | n=46, p50 42ms, p90 68ms, 최대 83ms |
| 댓글(10-26 이후 JSON 로그만) | n=8, p50 1,928ms, 최대 2,143ms | n=5, p50 61ms, 최대 80ms |
| AI(10-26 이후 JSON 로그만, 봇 글 포함 가능) | n=23, p50 1,426ms, 최대 2,364ms | n=9, p50 41ms, 최대 49ms |

같은 날 댓글 POST의 Lambda Duration은 첫 발행이 낀 요청 2,521ms·2,250ms, 같은 인스턴스의 다음 등록 99ms였다. 구간별로 재지는
않아서 "SDK 초기화가 대부분"은 첫 호출과 이후 호출의 차이에서 나온 추정이다.

`LambdaHandler.warmUpAwsSdk()`가 init에서 `SdkWarmUp.warmUp(LambdaClient, SesClient)`을 호출한다. AWS 문서는 SnapStart에서
이 방식을 권한다.

> `SdkWarmUp`은 HTTP 클라이언트를 데우려 네트워크 호출을 1번 하고, 서비스 클라이언트는 로컬의 준비된 응답으로 데운다. AWS 자격
> 증명이나 IAM 권한이 필요 없고 요금도 들지 않는다. Lambda는 핸들러 생성자가 끝난 뒤 스냅샷을 찍으므로 생성자에서 호출하면
> 스냅샷이 데워진 클라이언트를 포함한다. (번역, 요약)
>
> — AWS SDK for Java 2.x 개발자 안내서, https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/sdk-warmup.html

**실제로 하는 일(설치본 2.57.4를 `javap`로 확인).** 서비스 쪽은 `LambdaWarmUpProvider.warmUpClient`가 `CannedResponseHttpClient`,
`AnonymousCredentialsProvider`, `endpointOverride("http://localhost")`로 `listEventSourceMappings()`를 로컬에서 돈다. HTTP 쪽은
`SyncHttpClientWarmer`가 `https://sts.<AWS_REGION>.amazonaws.com/`에 GET을 1번 보내고, 실패는 debug 로그만 남긴다. 공급자 실패는
`WarmUpDiscovery.runSafely`가 warn(`Warm-up failed for X and was skipped.`)으로 남긴다.

**덮지 못하는 것.** 실제 `invoke`는 돌지 않고, 자격 증명은 anonymous라 실제 체인을 지나지 않으며, 복원 뒤 Lambda API로 가는 새
TLS 연결도 그대로다. 메일 쪽은 `MailService`가 실패 때만 로그를 남겨 전후 수치를 낼 수 없다.

**로컬 비교(직접 측정, 2026-11-05).** `shadowJar` 결과물 위에서 새 JVM(Corretto 17)마다 `LambdaSelfInvoker`와 같은 모양으로
클라이언트를 만들어 첫 `invoke` 시간을 쟀다. 가짜 자격 증명이라 AWS가 403으로 거절해 아무것도 실행되지 않는다. 각 5회의 p50이다.

| | 첫 호출(클라이언트 생성 포함) | 두 번째 호출 | `SdkWarmUp` 자체 |
| --- | --- | --- | --- |
| 워밍 없음 | 499ms | 48ms | - |
| 워밍 있음 | 188ms | 45ms | 682ms |

노트북은 Lambda가 아니고 복원 뒤 자격 증명 경로를 재현하지 못해 비율만 참고한다. 같은 비율이면 운영 첫 발행은 약 650ms로,
아래 목표에 못 미칠 수 있다(단순 환산, 측정 아님). `SdkWarmUp` 자체 시간은 버전 발행 때의 init에만 든다.

**구간 로그(2026-11-05 추가).** 1.7초의 구성은 재지 않은 추정이라, `LambdaSelfInvoker`가 인스턴스의 첫 발행에서 구간별 시간을 한 줄
남긴다. 둘째 발행부터는 찍지 않는다. 워밍이 들어간 뒤의 값이므로 원래 1.7초의 구성은 새 합계와의 차이로 간접적으로만 알 수 있다.

```
[LambdaSelfInvoker] 첫 발행 구간 - commentId: 1, totalMs=…, jsonMs=…, requestMs=…, clientMs=…, apiMs=…, credentialsMs=…, endpointMs=…, marshallingMs=…, signingMs=…, serviceCallMs=…, unmarshallingMs=…, retries=0
```

| 값 | 잰 구간 |
| --- | --- |
| `totalMs` | 아래 넷의 합에 가깝고, 위임 로그에서 `작업 발행` 로그까지의 간격과 거의 같다 |
| `jsonMs`, `requestMs` | 작업 내용 JSON 직렬화, `InvokeRequest` 조립 |
| `clientMs` | `by lazy` 클라이언트 생성 |
| `apiMs` | SDK `invoke` 전체(`ApiCallDuration`). 아래 값의 합을 뺀 나머지는 SDK 내부 처리다 |
| `credentialsMs`, `endpointMs`, `marshallingMs`, `signingMs`, `unmarshallingMs` | 자격 증명 읽기, 엔드포인트 결정, 요청 직렬화, 서명, 응답 해석 |
| `serviceCallMs` | 연결부터 응답 첫 바이트까지. 첫 호출 172ms, 둘째 66ms로 새 연결 비용이 여기 들어간다(직접 측정, 1회) |

로컬 구간 값(직접 측정, 2026-11-05). 실제 `LambdaSelfInvoker`를 로컬 가짜 Lambda API(`aws.endpointUrlLambda`)로 새 JVM마다 돌린 각 5회의
p50이다. TLS와 AWS 왕복이 없고 작업 내용이 단순한 `Map`이라 운영 값이 아니라 로그가 찍히는지와 구간의 크기만 본다.

| | `totalMs` | `jsonMs` | `requestMs` | `clientMs` | `apiMs` |
| --- | --- | --- | --- | --- | --- |
| 워밍 없음 | 199 | 9 | 4 | 122 | 58 |
| 워밍 있음 | 38 | 9 | 1 | 5 | 21 |

측정 코드 비용은 작다. 위 "로컬 비교"와 같은 방식(실제 TLS)으로 워밍 뒤 첫 호출 p50이 publisher 없이 189ms, 있으면 201ms였다(직접 측정,
각 5회, 오차 범위).

**판정 기준(우리 판단, 외부 기준 아님).** 새 버전의 첫 발행 5건 이상에서 p50 500ms 이하이고 이후 발행이 그대로면 달성으로 본다.
Restore Duration(기준선: 최근 14일 n=499, p50 684ms, p90 944ms, 최대 1,412ms)이 p50 기준 200ms 넘게 늘면 득실을 다시 본다.
못 미치면 구간 로그로 남은 비용을 보고 별도로 다룬다.

제약:
- 실패해도 부팅은 계속한다 — 이 최적화 때문에 배포가 막히지 않게 한다
- 실제 클라이언트는 `by lazy` 그대로다. `SdkWarmUp`은 별도 임시 클라이언트로 돈다
- 배포 관문(`deploy.yml`의 새 버전 직접 호출)은 `GET /api/post`만 불러 self-invoke를 지나지 않는다. 배포 뒤 `작업 발행` 로그의
  `statusCode`를 따로 확인한다
- `MetricPublisher.publish`에서 던진 예외는 SDK가 `invoke` 밖으로 그대로 던진다(2.57.4 직접 확인). 측정이 발행을 깨지 않게 publisher는
  값만 담고, 로그는 `invoke`가 끝난 뒤에 만든다

---

## 5. 사후 분석 — 워밍업 시도가 일으킨 502 장애 (2026-07-25)

### 증상

워밍업이 포함된 v40 배포 직후 `/api/*` 요청이 502. Lambda 로그에는
`lambda_runtime::layers::panic`, 응답 페이로드는 `client error (Connect)`.
지속 시간 약 2분(`prod` alias를 v39로 되돌려 복구).

### 실험으로 확인한 사실

`prod`를 건드리지 않고 버전을 직접 호출해 변수를 분리했다.

| 버전 | 구성 | 결과 |
| ---- | ---- | ---- |
| v40 | 워밍업 코드 + 2048MB | 1회차 성공 → 이후 100% panic |
| v41 | 워밍업 코드 + **1024MB** | 1회차 성공 → 이후 panic |
| v39 | 워밍업 없음 + 1024MB | 전부 정상 |

→ **메모리는 무관하고 워밍업 코드가 원인.** 패턴은 "복원 후 첫 요청은 성공, 이후 실패".

### 근본 원인 — 문서와 실제 아키텍처의 불일치

이 문서와 [DEPLOY.md](./DEPLOY.md)는 "Tomcat을 쓰지 않고 MockMvc로 처리한다"고 서술해 왔지만
**실제 배포본은 그렇지 않다.**

- 함수에 **Lambda Web Adapter 레이어**(`LambdaAdapterLayerArm64:24`)가 붙어 있다
- 기동 로그에 `Tomcat started on port 8080`이 찍힌다 (Tomcat이 실제로 뜬다)
- 실사용 요청은 **Tomcat 스레드**(`nio-8080-exec-N`)가 처리한다. LWA가 `127.0.0.1:8080`으로
  프록시하는 구조다

즉 `LambdaHandler`의 MockMvc 경로는 실트래픽 경로가 아니다. 이 상태에서 init 중에 MockMvc를
건드리면(`[Tomcat].[localhost].[/api] Initializing Spring TestDispatcherServlet ''`)
스냅샷에 담기는 Tomcat 서블릿 컨텍스트 상태가 바뀌고, 복원 후 LWA가 8080에 접속하지 못한다.

### 부수적으로 드러난 버그

워밍업 경로를 `/common/category-options`(복수)로 썼는데 실제 매핑은
`/common/category-option`(**단수**, `CategoryController`)이다. 404가 조용히 무시되어
정작 데우려던 Hibernate·HikariCP 워밍업은 처음부터 수행되지 않았다.

### 교훈

1. **코드 변경과 인프라 변경(메모리)을 한 배포에 섞지 말 것.** 변수가 둘이면 원인 분리에
   시간이 든다. 실제로 이번엔 버전을 따로 발행해서야 분리할 수 있었다.
2. **워밍업 응답 상태를 검증할 것.** 404가 조용히 지나가면 "워밍업했다"는 착각만 남는다.
3. **문서가 실제 배포 상태와 다를 수 있다고 가정할 것.** LWA 레이어의 존재를 미리 확인했다면
   MockMvc 워밍업이 위험하다는 걸 배포 전에 알 수 있었다.

### 다시 시도하려면

3장의 병목(복원 후 첫 요청 2.9초)은 그대로 남아 있다. 해결하려면 먼저 아키텍처를
정리해야 한다 — 둘 중 하나를 택한다.

- **LWA 레이어를 제거**하고 `LambdaHandler`(MockMvc)를 실제 진입점으로 만든다.
  문서가 서술해 온 구조와 일치하게 되지만, 서빙 경로가 통째로 바뀌므로 별도 버전으로
  충분히 검증한 뒤 alias를 옮겨야 한다.
- **LWA + Tomcat 구조를 유지**하고, 워밍업을 MockMvc가 아니라
  `http://127.0.0.1:8080`으로 실제 HTTP 요청을 보내 실서빙 경로를 데운다.

어느 쪽이든 `prod` alias를 옮기기 전에 버전 직접 호출(`aws lambda invoke --function-name
link-sphere-api:<버전>`)로 **연속 호출이 정상인지** 반드시 확인할 것. 첫 요청만 보면 놓친다.

### 진행 상황 — 1단계 완료 (2026-07-25)

**LWA 레이어를 제거했다** (v43, `prod` alias 이동 완료).

`AWS_LAMBDA_EXEC_WRAPPER`가 설정돼 있지 않아 LWA는 익스텐션으로만 떠서 `127.0.0.1:8080`을
폴링하고 있었고, 접속 실패 시 panic하며 호출 전체를 실패시켰다. 제거해도 되는 잔재였다.

검증 결과:

| 항목 | 결과 |
| ---- | ---- |
| 버전 직접 호출 8회 | 8/8 성공, panic 없음 |
| 응답 본문 v42 대비 | 완전 동일 (게시글 목록·구조·total 일치) |
| CloudFront 실경로 10회 | 10/10 200 (ttfb 0.20~0.42초) |
| 쿠키 전달 / 인증 401 / 쿼리스트링 | 전부 정상 |

이로써 **워밍업을 다시 넣을 수 있는 전제가 마련됐다**(2단계). 워밍업이 깨졌던 유일한 원인이
LWA였기 때문이다. 단, 2단계도 반드시 버전 발행 → 연속 호출 검증 → alias 이동 순으로 진행할 것.

### 진행 상황 — 2단계 완료 (2026-07-25)

**워밍업을 다시 넣었다** (v44, `prod` alias 이동 완료). v40 때와 달라진 점:

- 경로를 `/common/category-option`(**단수**)으로 수정 — v40은 복수형이라 404였다
- 응답이 2xx가 아니면 WARN을 남기도록 검증 추가 — 조용한 실패를 막는다
- CI 파이프라인 대신 **수동 배포**로 버전을 발행해, alias를 옮기기 **전에** 검증했다

검증 결과:

| 항목 | 결과 |
| ---- | ---- |
| 버전 직접 호출 12회 | 12/12 성공, panic 없음 |
| 워밍업 실행 로그 | `/common/category-option` 3회 (= `WARMUP_ITERATIONS`), **WARN 0건** = 전부 2xx |
| 응답 본문 v43 대비 | `data` 전체 완전 일치 |
| CloudFront 실경로 12회 | 12/12 200 |

**성능 효과는 아직 확정하지 못했다.** 같은 시점 단일 표본 비교로는 아래와 같으나,
콜드 표본이 버전당 1건이고 갓 발행한 스냅샷은 첫 복원이 느려 신뢰할 수 없다.

| 버전 | restore | 첫 요청 | 총합 |
| ---- | ------- | ------- | ---- |
| v43 (워밍업 없음) | 1,078ms | 5,855ms | 6,933ms |
| v44 (워밍업 있음) | 996ms | **3,135ms** | 4,131ms |

같은 조건(갓 발행한 스냅샷)에서 첫 요청이 5,855 → 3,135ms로 줄어든 것은 긍정적 신호지만,
**기준선 3,523ms와 직접 비교해선 안 된다**(측정 조건이 다르다). 정식 판단은 4단계에서
24시간 이상 쌓인 콜드 표본으로 한다.

---

## 6. 개선 후 수치

### 워밍업 효과 — 확인됨

7장의 권장 방식(엔드포인트 고정, `--log-type Tail`)으로 측정했다. **두 버전은 메모리가
같고 워밍업 유무만 다르다**(둘 다 1024MB, LWA 없음). 같은 요청(`GET /api/post`)을 각각
콜드 상태에서 1회씩 호출했다.

| 버전 | 구성 | restore | 첫 요청 | 총합 |
| ---- | ---- | ------- | ------- | ---- |
| v43 | 워밍업 없음 / 1024MB | 621ms | **2,210ms** | 2,831ms |
| v44 | 워밍업 있음 / 1024MB | 683ms | **1,046ms** | **1,729ms** |

**첫 요청 53% 감소, 총합 39% 감소.** 워밍업이 의도대로 초기화 비용을 스냅샷에 굽고 있다.

> 3장 기준선(총합 3,523ms)과 직접 비교하지 말 것 — 기준선은 7장에서 설명한 혼합 집단의
> p50이라 측정 대상이 다르다. 위 표는 동일 조건 단건 비교이며 이쪽이 해석 가능한 값이다.

### 메모리 2048 효과 — 판단 불가 (측정 편차가 너무 큼)

두 차례 측정이 **정면으로 모순됐다.**

| 버전 | 구성 | 1차 첫 요청 | 2차 첫 요청 (20분 유휴 후) |
| ---- | ---- | ----------- | -------------------------- |
| v43 | 워밍업 ✗ / 1024MB | 2,210ms | 6,314ms |
| v44 | 워밍업 ✓ / 1024MB | 1,046ms | 3,385ms |
| v47 | 워밍업 ✓ / 2048MB | 2,879ms | **446ms** |

1차에서는 2048이 1024보다 나빴고(2,879 vs 1,046), 2차에서는 훨씬 좋았다(446 vs 3,385).
**같은 버전인데 측정 간 편차가 2~6배**다.

1차 때 "v47이 느린 이유는 갓 발행한 스냅샷이라서"라고 적었으나 **이 설명은 틀렸다.**
2차에서는 더 오래된 v43·v44가 더 느렸고 더 새것인 v47이 가장 빨랐다. 스냅샷 나이로 설명되지
않는다. 실제 원인은 확인하지 못했다(Lambda 인프라 쪽 변동으로 추정될 뿐이다).

**교훈: 버전당 표본 1건으로는 메모리 같은 작은 효과를 판단할 수 없다.** 워밍업처럼 큰 효과
(46~53%)는 단건으로도 방향이 일관되게 나오지만, 그보다 작은 차이는 노이즈에 묻힌다.
정말 판단하려면 버전당 10회 이상, 매번 20분 이상 유휴를 두고 측정해야 한다.

메모리 2048은 v41 실험에서 **장애와 무관함이 입증됐고** 비용도 사실상 0이므로 유지한다.
성능 기여도만 미확정으로 남긴다 — 되돌릴 이유가 없어 이 값을 아는 것이 의사결정을 바꾸지 않는다.

### 콜드 발생 빈도

워밍 핑(4.3)이 낮추는 지표. 기준선 22%. 7장의 함정 때문에 로그 일괄 집계로는 판단할 수
없으므로, 판단하려면 워밍 핑을 일시 중지하고 실사용 트래픽에서만 표본을 모아야 한다.

---

## 7. 재측정 방법

> ⚠️ **아래 로그 기반 p50 방식은 신뢰할 수 없다.** 2026-07-25에 확인한 함정이다.
>
> CloudWatch `REPORT`에는 비용이 전혀 다른 엔드포인트가 한데 섞인다. 실측 1시간 표본에서
> `/post`(DB 조회) 76건, `/actuator/health`(워밍 핑, DB 미접근) 30건,
> `/common/category-option` 16건이 같은 집단에 있었다. 이 위에서 계산한 "콜드 첫 요청 p50"은
> 단일 지표가 아니라 서로 다른 작업의 혼합이다.
>
> 게다가 **워밍 핑이 가벼운 호출을 계속 추가하므로, 실제로 아무것도 빨라지지 않아도 p50이
> 개선된 것처럼 보인다.** 3장 기준선의 "첫 요청 p50 2,906ms"도 이 혼합값이므로 개선 후
> 수치와 직접 비교하면 안 된다.

### 권장 — 엔드포인트를 고정한 단건 비교

버전을 직접 호출하고 `--log-type Tail`로 그 호출의 `REPORT`를 바로 받는다.
`prod` alias를 건드리지 않으므로 서비스에 영향이 없다.

```bash
aws lambda invoke --function-name link-sphere-api:<버전> --log-type Tail \
  --payload fileb://event.json /tmp/out.json --query 'LogResult' --output text \
  | base64 -d | grep -E "REPORT"
```

`event.json`은 항상 같은 엔드포인트를 쓴다:

```json
{"rawPath":"/api/post","rawQueryString":"page=0&size=5","requestContext":{"http":{"method":"GET"}}}
```

**반드시 지킬 조건 두 가지**:

1. **비교 대상은 한 가지만 달라야 한다.** 워밍업 효과를 보려면 메모리가 같은 두 버전을
   비교한다(v43 vs v44는 둘 다 1024MB라 워밍업만 다름).
2. **단건 측정으로 작은 차이를 판단하지 말 것.** 같은 버전을 다른 시점에 재보면 첫 요청이
   2~6배까지 달라진다(6장 표). 46~53% 수준의 큰 효과는 단건으로도 방향이 일관되게 나오지만,
   그보다 작은 차이는 노이즈에 묻힌다. 작은 효과를 보려면 버전당 10회 이상, 매번 20분 이상
   유휴를 두고 측정해야 한다.

`publish-version`은 코드·설정이 이전과 같으면 **새 버전을 만들지 않고 기존 버전을 반환한다.**
콜드를 강제하려는 목적이라면 `--description`이라도 바꿔야 실제로 새 스냅샷이 생긴다.

### 참고 — 기존 로그 일괄 집계 (분포 파악용으로만)

새 버전을 `publish-version` 하면 스냅샷이 새로 만들어지므로 첫 호출은 반드시 콜드다.

```bash
aws logs filter-log-events \
  --log-group-name /aws/lambda/link-sphere-api \
  --region ap-northeast-1 \
  --start-time $(( ($(date +%s) - 172800) * 1000 )) \
  --filter-pattern 'REPORT' --max-items 600 \
  --query 'events[].message' --output text > rep.txt
```

**콜드/웜 분리 기준**: `REPORT` 블록에 `Restore Duration` 필드가 있으면 콜드, 없으면 웜.
콜드 총합 = `Restore Duration` + `Duration`.

```bash
python3 - <<'EOF'
import re, statistics
t = open("rep.txt").read().replace('\t', '\n')
cold, warm, rest = [], [], []
for b in re.split(r'(?=REPORT RequestId)', t):
    if not b.startswith('REPORT'):
        continue
    m = re.search(r'Duration: ([\d.]+) ms', b)
    if not m:
        continue
    d = float(m.group(1))
    r = re.search(r'Restore Duration: ([\d.]+) ms', b)
    if r:
        cold.append(d); rest.append(float(r.group(1)))
    else:
        warm.append(d)

def s(name, a):
    if not a:
        print(name, "none"); return
    a = sorted(a)
    p90 = a[max(0, int(len(a) * .9) - 1)]
    print(f"{name:24} n={len(a):3} p50={statistics.median(a):7.0f} p90={p90:7.0f} max={a[-1]:7.0f} ms")

s("COLD 1st-request", cold)
s("  SnapStart restore", rest)
s("WARM", warm)
if cold:
    s("COLD TOTAL", [c + r for c, r in zip(cold, rest)])
    print(f"cold rate: {100 * len(cold) / (len(cold) + len(warm)):.0f}%")
EOF
```

콜드 비율은 트래픽 패턴에 좌우되므로 **워밍 핑 적용 후 24시간 지나서** 다시 본다.

### 첫 발행 지연 (4.6)

`/aws/lambda/link-sphere-api` 로그 그룹의 Logs Insights로 아래 줄을 받는다. 읽기 전용이다.

```
fields @timestamp, @logStream, @message
| filter @message like /LambdaSelfInvoker\] 작업 발행|별도 Lambda 호출로 위임/
| sort @timestamp asc | limit 10000
```

로그 스트림(인스턴스)별로 시간순으로 놓고, 위임 로그 뒤 처음 나오는 `작업 발행` 로그까지의 간격을 잰다. 스트림에서 첫 `작업 발행`이면
"첫 발행", 그 뒤면 "이후 발행"으로 나눈다. 피드 발행은 위임 로그 없이 `작업 발행`만 찍히므로 간격을 내지 않고 순번에만 센다.
종류 구분은 메시지의 `[AI]`·`[Comment PostProcess]`로 하고, 2026-10-26 전 로그는 텍스트 형식이라 태그 구분에서 뺀다. 복원 시간은 함께 본다.

```
filter @type = "REPORT" and @message like /Restore Duration/
| parse @message /Restore Duration: (?<r>[0-9.]+) ms/
| stats count() as n, pct(r, 50) as p50, pct(r, 90) as p90, max(r) as maxR
```

배포 시각을 시작 시간으로 잡으면 새 버전만 잡힌다. 구간별 시간은 구간 로그를 쪼개 본다.

```
filter @message like /첫 발행 구간/
| parse @message /totalMs=(?<total>\d+), jsonMs=(?<json>\d+), requestMs=(?<req>\d+), clientMs=(?<client>\d+), apiMs=(?<api>\d+), credentialsMs=(?<cred>\d+), endpointMs=(?<endpoint>\d+), marshallingMs=(?<marshal>\d+), signingMs=(?<sign>\d+), serviceCallMs=(?<call>\d+), unmarshallingMs=(?<unmarshal>\d+)/
| stats count() as n, pct(total, 50) as totalP50, pct(json, 50) as jsonP50, pct(req, 50) as reqP50, pct(client, 50) as clientP50, pct(api, 50) as apiP50, pct(cred, 50) as credP50, pct(sign, 50) as signP50, pct(call, 50) as callP50
```

---

## 8. 비용 — Cost Explorer 실측 (추정치 아님)

이 계정은 프리티어가 아니라 실과금 상태다. 처음에는 사용량 × 공시 단가로 **추정**했으나,
`ce:GetCostAndUsage` 권한을 얻은 뒤 실제 청구액을 확인해 아래로 대체했다.

**2026년 7월 1~25일 실제 청구액 (서비스별)**

| 서비스 | 금액 | 비고 |
| ------ | ---- | ---- |
| **Amazon VPC** | **$2.835** | **전체의 81%** — 이 프로젝트에서 쓰지 않는다 |
| Tax | $0.310 | |
| **AWS Secrets Manager** | **$0.305** | 이 프로젝트는 Lambda 환경변수를 쓴다 |
| Amazon ECR | $0.020 | 컨테이너 이미지 배포 시절 잔재 |
| Amazon S3 | $0.008 | 배포 버킷 + FE 호스팅 |
| **AWS Lambda** | **$0.0000004** | 사실상 0 |
| CloudFront / CloudWatch / KMS / Glue | $0 | |
| **합계** | **$3.478** | |

**결론이 뒤집힌 지점**: Lambda 최적화의 비용 영향은 **측정 불가능할 만큼 작다**($0.0000004).
반면 **실제 청구서의 약 90%(VPC $2.84 + Secrets Manager $0.31 + ECR $0.02)는 이 프로젝트가
쓰지 않는 리소스**에서 나온다. VPC·ECR 과금은 App Runner / 컨테이너 이미지 배포를 시도했다가
Lambda zip 방식으로 전환한 시절의 잔재로 보인다(NAT Gateway 등).

**추정이 빗나간 이유**: Lambda 컴퓨트를 $0.021/월로 계산했으나 실제는 $0.0000004였다.
사용량 실측(1,555 GB-초)은 맞았지만 프리티어가 일부 적용되는 것으로 보인다.
S3도 $0.083으로 추정했으나 실제는 $0.008이었다. **사용량 × 공시 단가는 상한선일 뿐이며,
실제 청구액과 대조하기 전까지는 결론을 내리지 말 것.**

**비용을 실제로 줄이려면** Lambda가 아니라 VPC(NAT Gateway 등)와 Secrets Manager를 봐야 한다.
이 문서의 범위 밖이므로 별도 과제로 남긴다.

```bash
# 서비스별 실제 청구액 확인 (us-east-1 고정)
aws ce get-cost-and-usage --time-period Start=2026-07-01,End=2026-07-25 \
  --granularity MONTHLY --metrics UnblendedCost \
  --group-by Type=DIMENSION,Key=SERVICE --region us-east-1 \
  --query 'ResultsByTime[0].Groups[].[Keys[0],Metrics.UnblendedCost.Amount]' --output text
```

참고로 남겨두는 사실:

- **메모리 2배가 요금 2배가 아니다.** Lambda는 `GB × 초` 과금인데 메모리를 올리면 vCPU도
  올라가 실행 시간이 줄어 상쇄된다 → GB ×2, 초 ×0.6 → 실질 거의 중립.
- **SnapStart는 Java 런타임에 추가 과금이 없다**
  (AWS 요금 페이지: "SnapStart pricing does not apply to supported Java managed runtimes").
- S3 배포 버킷에 배포마다 85MB jar가 정리 없이 쌓이고 있었다(39개 / 3.31GB).
  금액은 작지만($0.008/월) 계속 증가하므로 30일 만료 규칙을 걸었다 → [DEPLOY.md](./DEPLOY.md).

---

## 9. FE 쪽 병목 (참고)

BE만 고쳐서는 체감이 다 낫지 않았다. FE가 콜드스타트를 직렬로 증폭하고 있었다.

`AuthProvider`가 라우터 전체를 `POST /auth/refresh` 뒤에 블로킹했고, auth store가 persist가
아니라 **비로그인 방문자도 매번** 이 호출을 했다. 콜드 람다를 때리면
`3.5초 풀스크린 스피너 → 라우트 청크 다운로드 → 목록 조회` 순으로 완전 직렬이었다.

FE 대응은 링크스피어 FE 레포의 `docs/DECISIONS.md`(2026-07-25 항목) 참고.
