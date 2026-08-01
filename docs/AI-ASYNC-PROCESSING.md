# Link-Sphere BE — 게시글 AI 분석 비동기화 (2026-08-01)

## 1. 문제

`POST /post`는 크롤링 이후 Gemini 요약·카테고리 분류가 끝날 때까지 응답을 미룬 채
동기로 기다리는 구조였다(`PostAIService.handlePostCreatedEvent`,
`@TransactionalEventListener(AFTER_COMMIT)` + 동기 호출). 크롤링과 AI 처리 합산
시간이 CloudFront origin timeout(30초)을 넘기면 클라이언트는 504를 받지만, Lambda
자체는 계속 실행되어 게시글은 이미 DB에 커밋된 채 **응답만 유실**되는 문제가 있었다.

실제 사고 사례(2026-07-31 02:20~02:21 UTC, `postId b61c9b90...`):

| 시각 | 내용 |
| ---- | ---- |
| 02:20:36 | 크롤링·커밋 완료 |
| 02:20:36~02:20:46 | Gemini 요약 호출 (9.6초) |
| 02:20:46~02:21:11 | Gemini 카테고리 분류 호출 (**25.2초**, 비정상적으로 느림) |
| 02:21:12 | `ObjectOptimisticLockingFailureException` — 이 사이 사용자가 같은 post를 삭제함 |
| — | 전체 요청 소요 시간 **35.9초** — CloudFront 30초 한도를 넘겨 클라이언트는 504 수신 |

### 왜 처음부터 비동기로 만들지 않았는가

과거(`80d8861` → `55b2bcc` → `c9de0f5`)에 `@Async` + SSE로 비동기 처리를 구현한
적이 있다. `e23fa0e fix: Lambda 환경 안정성 개선` 커밋에서 의도적으로 되돌렸는데,
이유는 커밋 메시지에 명시돼 있다:

> Lambda는 `handleRequest()` 반환 후 컨테이너를 동결하므로 비동기 스레드가
> 완료되기 전에 AI 처리가 중단될 수 있음. 동기 처리로 변경해 POST /post 응답 전에
> AI 분석 완료 보장

`@Async`는 Spring `TaskExecutor`로 작업을 던지는데, 이 스레드는 **같은 Lambda
실행 환경(같은 컨테이너) 안의 또 다른 스레드**일 뿐이다. Lambda는 `handleRequest()`가
리턴하는 순간 그 컨테이너 전체(모든 스레드 포함)를 얼려버리므로, 백그라운드 스레드가
Gemini 응답을 기다리는 도중 그대로 멈춰버릴 수 있었다. SSE(`GET /post/ai-events`)도
같은 이유로 죽었다 — 커넥션을 열어두고 나중에 push하려면 컨테이너가 계속 살아있어야
하는데, 그 전제 자체가 깨진다.

## 2. 해결 방향 — Lambda self-invoke

"같은 실행 환경 안에서 스레드만 백그라운드로 돌리기"가 아니라 **완전히 별도의
Lambda 호출로 위임**하면 이 제약을 피할 수 있다.

- `PostService.createPost()` 커밋 후, `PostAIService.handlePostCreatedEvent`가
  `AiJobDispatcher`를 통해 이 Lambda 함수 자기 자신을 `InvocationType.EVENT`
  (비동기)로 호출한다.
- 이 호출은 AWS Lambda 컨트롤 플레인에 작업을 "접수"시키고 202 Accepted를
  받는 것으로 끝난다 — Gemini를 호출하는 것과 성격이 같은 평범한 동기 외부 HTTP
  호출이며, 202를 받는 순간 원래 요청의 컨테이너가 얼든 말든 상관없다.
- AWS는 이 작업을 위해 완전히 별도의 실행 환경을 새로 띄운다. 원래 요청을
  처리하던 컨테이너와 메모리·스레드·생명주기를 전혀 공유하지 않는다.
- `LambdaHandler.handleRequest`는 페이로드의 `linksphereJob` 필드로 일반 HTTP
  이벤트와 이 내부 작업 이벤트를 구분해서, 후자면 MockMvc를 거치지 않고
  `PostAIService.processAiJob`을 직접 호출한다.

```
POST /post
  → 크롤링 → DB 커밋 → AiJobDispatcher.dispatch() [202 Accepted, ~1~2초]
  → 응답 반환 (크롤링만 끝나면 바로 나감)

(완전히 별도의 Lambda 실행 환경)
  → LambdaHandler.handleAiJob()
    → PostAIService.processAiJob()
      → Gemini 요약 + 카테고리 분류 (병렬)
      → DB 저장 (aiStatus: PENDING → COMPLETED/FAILED)
```

요약과 카테고리 분류도 순차 대신 병렬로 실행한다(`GeminiService.analyzeContentAsync`,
`PostCategoryClassifier.classifyAsync`, 기존 `AsyncConfig`의 `ai-async-` 스레드풀
재사용). 카테고리 분류 입력은 요약이 만들어낼 AI 태그 대신 크롤링 시점의 기존 태그만
사용하도록 바꿨다 — 순차 의존을 끊어야 병렬화가 가능하기 때문이며, 태그 매칭 1차
필터는 기존 태그만으로도 대체로 충분하다는 판단.

관련 파일: `AiJobDispatcher`, `LambdaHandler.handleAiJob`,
`PostAIService.processAiJob`, `GeminiService.analyzeContentAsync`,
`PostCategoryClassifier.classifyAsync`

## 3. 시행착오 1 — self-invoke가 `$LATEST`를 타던 문제

`AiJobDispatcher`가 `InvokeRequest`에 qualifier를 지정하지 않고 배포했더니, AWS가
기본값인 `$LATEST`로 호출했다. `application.yml`의 SnapStart는
`ApplyOn=PublishedVersions`로 설정돼 있어 **`$LATEST`엔 스냅샷 최적화가 적용되지
않는다** — EventBridge 워밍 핑에서 이미 겪었던 것과 같은 함정이다([DEPLOY.md](./DEPLOY.md)
6장 참고).

실측(1차 배포, `$LATEST`로 실행됨):

```
START RequestId: 83232e69... Version: $LATEST
REPORT ... Duration: 25373.84 ms
```

`InvokeRequest`에 `.qualifier("prod")`를 명시해 발행된 버전(SnapStart 적용
대상)으로 호출하도록 수정 후 재검증:

```
START RequestId: 021ab36d... Version: 56
REPORT ... Duration: 10293.71 ms   (Restore Duration 없음 — 웜 컨테이너 재사용)
```

IAM 정책도 qualifier 포함 여부에 따라 리소스 ARN 매칭이 달라진다는 점에 주의.
`arn:...:function:link-sphere-api`(qualifier 없음)와 `arn:...:function:link-sphere-api:prod`는
IAM 입장에서 다른 문자열이라, 정책의 `Resource`가 정확히 전자로만 돼 있으면 후자
호출은 매치되지 않아 `AccessDenied`가 난다. 이 저장소의 GitHub Actions 배포용
IAM 정책([DEPLOY.md](./DEPLOY.md) 1절)도 정확히 이 이유로 끝에 와일드카드를 붙여둔다:

```json
"Resource": "arn:aws:lambda:ap-northeast-1:*:function:link-sphere-api*"
```

`AiJobDispatcher`가 self-invoke할 때 필요한 `lambda:InvokeFunction` 권한도 같은
패턴(`arn:aws:lambda:ap-northeast-1:<account>:function:link-sphere-api*`)으로
Lambda 실행 역할에 부여해야 한다.

## 4. 시행착오 2 — AI 처리 중 삭제 시 예외 처리 (핵심)

### 4.1 증상

AI 분석(최대 45초 소요 가능)이 끝나기 전에 사용자가 같은 게시글을 삭제하면 어떻게
되는지가 문제였다. `postRepository.save()`는 UPDATE SQL을 즉시 실행하지 않고
**트랜잭션 커밋 시점까지 지연**시킨다. 그 사이 다른 요청이 같은 row를 삭제하면,
커밋 시점에 Hibernate가 "UPDATE가 0 rows에 적용됨"을 감지해
`ObjectOptimisticLockingFailureException`(원인: `StaleObjectStateException`)을
던진다.

문제는 이 예외가 발생하는 시점이다 — Kotlin 코드 상의 `try/catch` 블록은 메서드
본문만 감싸고 있는데, 실제 flush/commit은 `@Transactional` 프록시가 메서드 본문이
끝난 **뒤**에 수행한다. 즉 `save()`만 쓰면 이 예외는 우리 코드의 `try/catch` **바깥**에서
터진다.

- 과거 동기 구조에서는 이 예외가 Spring의 `TransactionSynchronizationUtils`
  내부에서 로그만 남기고 조용히 삼켜졌다(사용자·운영자 모두 눈치채기 어려운
  "조용한 실패").
- 오늘 만든 비동기 self-invoke 구조에서는 같은 예외가 `processAiJob`의
  `@Transactional` 커밋 실패로 이어져 `handleAiJob`까지 전파되고, Lambda가 이
  호출을 실패로 판단해 **불필요하게 재시도**(최대 2회)했다.

### 4.2 1차 수정 — `saveAndFlush` (불완전)

"UPDATE를 즉시 실행시키면 예외도 그 자리에서 터질 테니 잡을 수 있다"는 생각으로
`save()` → `saveAndFlush()`로 바꾸고, `catch (e: ObjectOptimisticLockingFailureException)`
블록에서 로그만 남기고 조용히 넘어가도록 했다.

실제 배포 후 재현하자 새로운 예외가 로그에 나타났다:

```
ERROR o.s.t.s.TransactionSynchronizationUtils : TransactionSynchronization.afterCompletion threw exception
org.springframework.transaction.UnexpectedRollbackException:
Transaction silently rolled back because it has been marked as rollback-only
```

### 4.3 원인 — 트랜잭션은 예외를 "잡았다고" 해서 되돌릴 수 없다

`saveAndFlush()`가 던지는 예외는 Hibernate의 `EntityManager`/세션이 이미
**사용 불가능한 상태로 오염**됐다는 신호다. 이 시점에 Spring의 트랜잭션 인프라는
현재 트랜잭션을 `rollback-only`로 표시한다. 애플리케이션 코드에서 이 예외를
`catch`해서 삼키고 메서드가 정상적으로 `return`하더라도, 그 표시는 지워지지
않는다.

메서드가 예외 없이 정상 종료되면 `@Transactional` 프록시(`TransactionInterceptor`)는
당연히 **커밋을 시도**한다. 하지만 트랜잭션 매니저가 커밋 직전에 `rollback-only`
표시를 발견하면, "커밋하라고 했는데 이미 롤백 예정으로 표시돼 있다"는 모순된
상태를 `UnexpectedRollbackException`으로 알린다.

**결론: 같은 트랜잭션 경계 안에서 flush 예외를 catch하고 정상적으로 계속
진행하는 것은 불가능하다.** 예외가 발생한 이상 그 트랜잭션은 반드시 롤백으로
끝나야 하며, "괜찮으니 계속 진행"이라는 선택지는 없다.

### 4.4 최종 수정 — 트랜잭션 경계 밖에서 흡수

접근을 바꿨다: 트랜잭션 안에서 억지로 수습하려 하지 않고, 예외를 그대로
인정해 정상적으로 롤백시킨 뒤, "이건 에러가 아니라 정상적인 레이스였다"는
판단은 트랜잭션이 완전히 끝난 **호출자 쪽**에서 내린다.

```kotlin
// PostAIService.kt — @Transactional 경계 "안"
} catch (e: ObjectOptimisticLockingFailureException) {
    logger.info("[AI] 분석 중 post가 삭제됨 - postId: $postId")
    throw e   // 삼키지 않고 다시 던진다 → 트랜잭션 매니저가 정상 롤백 경로를 탐
} catch (e: Exception) {
    logger.error("[AI] 분석 실패 - postId: $postId", e)
    post.aiStatus = AiStatus.FAILED
    postRepository.saveAndFlush(post)   // 이것도 같은 이유로 실패하면 자연스럽게 전파됨
}
```

```kotlin
// LambdaHandler.kt — 트랜잭션이 이미 정리된 "바깥"
private fun handleAiJob(event: JsonNode, output: OutputStream) {
    val payload = mapper.treeToValue(event.get("event"), PostCreatedEvent::class.java)
    val postAIService = applicationContext.getBean(PostAIService::class.java)
    try {
        postAIService.processAiJob(payload)
    } catch (e: ObjectOptimisticLockingFailureException) {
        // 여기는 트랜잭션 밖이므로 안전하게 흡수해도 된다.
        logger.info("[AI Job] 처리 중 post가 삭제됨 - postId: ${payload.postId}")
    }
    mapper.writeValue(output, mapOf("statusCode" to 200, "body" to "ok"))
}
```

`throw e`로 다시 던지면 `TransactionInterceptor`가 "예외가 났으니 롤백해야
한다"는 정상 경로를 타서, `UnexpectedRollbackException` 없이 깔끔하게
롤백된다. 트랜잭션이 완전히 정리된 뒤인 `LambdaHandler` 레벨에서 그 예외를
받아 로그만 남기고 200으로 응답하면, Lambda는 이 호출을 성공으로 처리하고
재시도하지 않는다.

`findById`로 post를 아예 못 찾는 최초 케이스(AI 작업이 위임된 뒤 처리되기 전에
이미 삭제된 경우)도 같은 근본 원인의 다른 타이밍일 뿐이므로 로그 레벨을
`error` → `info`로 하향했다.

### 4.5 검증 (실제 프로덕션 로그)

등록 직후 AI 처리가 끝나기 전에 게시글을 삭제해 재현:

```
04:08:53 - PostCreatedEvent 발행, AI 작업 발행 (statusCode: 202)
04:09:11 - [AI] 분석 중 post가 삭제됨          ← PostAIService, 로그 남기고 재던짐
04:09:11 - [AI Job] 처리 중 post가 삭제됨      ← LambdaHandler, 최종 흡수
```

`ERROR`, `UnexpectedRollbackException`, 재시도 없이 INFO 로그 두 줄로 종료됨을
확인했다.

## 5. 교훈

1. **Lambda에서 "응답 후에도 계속 처리"가 필요하면 스레드가 아니라 별도
   invocation으로 위임해야 한다.** `@Async`/SSE 등 같은 실행 환경 안에서
   완결을 시도하는 방식은 전부 "응답 후 컨테이너 동결" 문제를 겪는다.
2. **self-invoke는 반드시 qualifier(alias)를 명시해야 한다.** 비워두면
   `$LATEST`로 가는데, SnapStart는 발행된 버전에만 적용되므로 매 호출이
   완전 콜드스타트를 문다. IAM 정책의 리소스 ARN도 qualifier 유무에 따라
   다른 문자열로 매칭되므로 와일드카드가 필요하다.
3. **JPA/Hibernate flush 예외는 트랜잭션을 오염시킨다 — catch로 "복구"할 수
   없다.** 예외가 발생한 트랜잭션은 반드시 롤백으로 끝나야 하며, 그 예외를
   "정상 상황이었다"고 판단하는 로직은 트랜잭션 경계 **밖**(호출자)에 둬야
   한다. `save()`처럼 flush를 지연시키는 메서드를 쓰면 예외가 기대한
   위치(자신이 작성한 try/catch)에서 발생하지 않을 수 있다는 점도 함께
   기억할 것 — 즉시 flush가 필요하면 `saveAndFlush()`를 명시적으로 써야
   한다.
