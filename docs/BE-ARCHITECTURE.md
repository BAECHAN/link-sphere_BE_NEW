# Link-Sphere BE — Architecture & Pattern Guide

> **문서 성격**: 레퍼런스 — "지금 무엇으로, 왜 이렇게 구성되어 있는가"
>
> **대상 독자**: 이 레포 BE를 처음 보거나, 새 엔드포인트·도메인·배치 잡·외부 클라이언트를 추가하려는 사람과 Claude
>
> **읽고 나면**: 요청이 어느 코드를 거치는지 따라갈 수 있고, 자동 검사(§2)와 선례 패턴(§7)에 맞춰 새 코드를 쓸 수 있다.
> 지금 코드가 규칙과 어긋나는 곳은 §9에 모아 두었다 — 그쪽을 본떠 쓰지 않는다.
>
> **마지막 검토**: 2026-10-25

시스템 전체(CloudFront·S3·Lambda·배포 파이프라인)는 FE 레포
[`docs/SYSTEM-ARCHITECTURE.md`](https://github.com/BAECHAN/link-sphere_FE_NEW/blob/main/docs/SYSTEM-ARCHITECTURE.md)가,
파일 트리와 API 목록은 루트 [`README.md`](../README.md#프로젝트-구조)가 정본이다 — 여기에 옮겨 적지 않는다. 이 문서의
근거가 된 전체 점검 기록은 [`docs/plans/2026-10-25-be-architecture-audit.md`](plans/2026-10-25-be-architecture-audit.md).

---

## 1. 요청 파이프라인

Lambda 한 함수가 두 종류의 입력을 받는다. **HTTP 요청**은 MockMvc로 Spring MVC를 그대로 태우고, **내부 잡**
(`linksphereJob` 필드가 있는 이벤트)은 보안 필터·MockMvc를 건너뛰고 Service 빈을 직접 부른다.

```mermaid
flowchart TD
  CF["CloudFront /api/* (OAC 서명)"] --> URL["Lambda Function URL<br/>prod alias"]
  EB["EventBridge 룰 (4일마다)"] --> H
  URL --> H{"LambdaHandler.handleRequest<br/>linksphereJob 있나?"}
  H -->|"있음"| JOB["잡 분기 — Service 직접 호출<br/>보안 필터·MockMvc 미경유"]
  H -->|"없음"| G["FunctionUrlOriginGuard<br/>→ /api 제거 → MockMvc"]
  G --> F["SessionAuthenticationFilter<br/>토큰 해시로 매 요청 DB 조회"]
  F --> C["@RestController<br/>인증 추출 · 레이트리밋 · 위임"]
  C --> S["Service (@Transactional)<br/>검증 · 권한 · 예외"]
  S --> R["Repository (JPA)"] --> DB[("PostgreSQL")]
  S -.->|"예외"| EH["GlobalExceptionHandler<br/>ErrorResponse(status, code, message)"]
  S -.->|"커밋 뒤 이벤트"| AC["@TransactionalEventListener(AFTER_COMMIT)"]
  AC --> D["*JobDispatcher → LambdaSelfInvoker<br/>EVENT(비동기) 호출, prod alias"]
  D -->|"ai-analysis · comment-postprocess · feed-item"| H
  JOB --> S
```

| 잡(`linksphereJob`) | 누가 보내나 | 받는 Service |
|---|---|---|
| `ai-analysis` | 글 등록·수정 커밋 뒤 `PostAIService.handlePostCreatedEvent` → `AiJobDispatcher` | `PostAIService.processAiJob` |
| `comment-postprocess` | 댓글 등록·답글·수정 커밋 뒤 `CommentPostProcessService.handleCommentPostProcess` → `CommentJobDispatcher` | `CommentPostProcessService.processCommentJob`(FCM 알림·링크 프리뷰) |
| `feed-crawl` | EventBridge `link-sphere-feed-crawl` 룰 | `FeedCrawlService.collectAndDispatch` → `FeedJobDispatcher` |
| `feed-item` | `FeedCrawlService`(5건씩) | `FeedItemProcessor.processFeedItem` → `PostService.createPost` → 다시 `ai-analysis` |
| `account-purge` | 같은 EventBridge 룰 | `AccountPurgeService.purgeExpired` |
| `orphan-image-gc` | 같은 EventBridge 룰(`dryRun` 지원) | `OrphanImageGcService.collect` |

- self-invoke를 쓰는 이유(같은 실행 환경에서 이어 처리하면 응답 반환 뒤 컨테이너가 얼어붙는다)와 PENDING 고착 복구는
  [`AI-ASYNC-PROCESSING.md`](AI-ASYNC-PROCESSING.md), 룰 조작은 [`DEPLOY.md`](DEPLOY.md), 콜드스타트·SnapStart는
  [`PERFORMANCE.md`](PERFORMANCE.md).
- `tools/*Runner.kt`는 `@Profile("<이름>")` `CommandLineRunner`로, 로컬에서
  `./gradlew bootRun --args='--spring.profiles.active=secret,<이름>'`로만 도는 1회성 도구다. Lambda의 `app.run()`도
  `CommandLineRunner`를 실행하므로 **`@Profile` 가드를 빼면 운영에서 돈다.**

### 패키지 역할

| 패키지 | 담는 것 | 의존해도 되는 곳 |
|---|---|---|
| 루트 | `LambdaHandler`(진입점·잡 분기), `FunctionUrlOriginGuard`, `MultipartRequestParser` | 전부 |
| `domain/<도메인>/` | 도메인별 평면 구조 — Controller·Service·Repository·`Table*` 엔티티·`*DTO.kt`와 그 도메인 전용 헬퍼 | 다른 도메인(§3), `global`, `infra` |
| `global/common/` | 응답 래퍼, `SecurityUtils`, `SecureToken`, `ClientIpResolver`, `Paging`, `RateLimitService`, `SupabaseStorageService` | `domain` 금지(허용 3쌍 예외, §3) |
| `global/config/` | Security·Swagger·Async 설정 | 위와 같음 |
| `global/exception/` | 커스텀 예외 전부 + `GlobalExceptionHandler` | — |
| `infra/<기술>/` | 외부 연동(Gemini·YouTube·SES·FCM·Lambda self-invoke) | `domain`은 이벤트 DTO만(디스패처) |
| `tools/` | 로컬 1회성 CLI | 전부 |

도메인 안에는 서브패키지를 만들지 않는다(`infra/<기술>/dto/`만 예외). 도메인·`global` 하위는 단수 소문자
(`post/`, `common/`), `infra` 하위는 기술·서비스명 그대로(`ai/`, `fcm/`, `youtube/`)다.

---

## 2. 자동 검사가 강제하는 규칙

글로만 있는 규칙은 낡는다 — 이 레포의 아키텍처 규칙은 아래 테스트로 강제한다. `./gradlew test`(PR CI·배포 모두)가
깨지면 규칙을 어긴 것이다. 규칙을 바꾸려면 테스트와 이 표를 함께 고친다.

| 규칙 (테스트) | 막는 것 | 파일 |
|---|---|---|
| `controllersMustNotDependOnRepositories` | `@RestController`가 Repository·`*RepositoryImpl`을 직접 참조(앱 전체) | `architecture/AppWideArchitectureRulesTest.kt` |
| `repositoriesMustNotConstructExceptions` | Repository·`*RepositoryImpl`이 예외를 생성 | 위와 같음 |
| `globalMustNotDependOnDomain` | `global` → `domain` 의존(허용 3쌍 제외) | 위와 같음 |
| `controllerMustNotDependOnRepository` | 위 첫 규칙의 domain 한정판(먼저 있던 것) | `architecture/ArchitectureRulesTest.kt` |
| `servicesMustNotHaveCrossDomainCycles` | 도메인 Service 사이 순환 의존(빈 생성 실패로만 드러남) | 위와 같음 |
| `repositoryDeleteMethodsMustBeBulk` | derived delete(`deleteBy…`) — `@Modifying @Query` 일괄 DELETE만 | `architecture/RepositoryDeleteRulesTest.kt` |
| `global exception 패키지의 모든 예외에 @ExceptionHandler가 있다` | 등록 안 된 예외가 500으로 떨어짐 | `global/exception/ExceptionHandlerCoverageTest.kt` |
| `응답 상태코드에 숫자 리터럴을 쓰지 않는다` | `ApiResponse(200, …)`·`ResponseEntity.status(401)` — 상수 인자는 바이트코드로 구별되지 않아 소스 텍스트를 스캔 | `architecture/HttpStatusLiteralTest.kt` |
| `모든 컨트롤러에 @Tag 가, 모든 엔드포인트에 @Operation summary 가 있다` | Swagger 문서 누락 | `global/config/SwaggerDocsCoverageTest.kt` |

ArchUnit 규칙은 대상 클래스가 0개면 실패한다(`archRule.failOnEmptyShould` 기본값, 2026-10-25 빈 대상 규칙을 일부러
넣어 확인) — 패키지가 바뀌어도 조용히 통과하지 않는다. 클래스패스 스캔 테스트는 최소 개수 하한선을 둔다.

**지금 위반이 남아 있어 아직 켜지 않은 규칙** — §9의 정리 PR에서 수정과 함께 켠다:

| 예정 규칙 | 막을 것 | 켜는 PR |
|---|---|---|
| `IllegalArgumentException` 생성 금지 | IAE가 404 `NOT_FOUND`로 바뀌어 응답 코드가 틀어짐 | 에러 코드 정리 |
| `Principal`·`@AuthenticationPrincipal` 파라미터 금지, `getName()`은 `SecurityUtils`에서만 | 사용자 id를 문자열로 직접 파싱 | 사용자 추출 통일 |
| `Authentication?`는 `@SecurityRequirements`가 붙은 공개 엔드포인트에만 | 인증 필수 엔드포인트의 nullable 파라미터 | 위와 같음 |
| 매핑 메서드는 `ApiResponse<*>` 또는 `ResponseEntity<ApiResponse<*>>` 반환 | 응답 래퍼를 안 쓰는 엔드포인트 | 위와 같음 |

---

## 3. 레이어 규칙과 허용 예외

| 파일 | 역할 | 하지 않는 것 |
|---|---|---|
| `*Controller.kt` | 인증 추출 → (필요하면) 레이트리밋 → Service 위임 → `ApiResponse` 반환 | Repository 참조, 비즈니스 분기, 숫자 상태코드 |
| `*Service.kt` | 검증 · 권한 · 예외 throw · DTO 변환 · 트랜잭션 경계 | HTTP 개념(쿠키·헤더) |
| `*Repository.kt` | 쿼리 메서드(파생·JPQL·네이티브) | 예외 throw, 비즈니스 판단 |
| `*RepositoryCustom` + `*RepositoryImpl` | Criteria API가 필요한 동적 검색(`PostRepositoryImpl`, `BookmarkRepositoryImpl`) | 위와 같음 |
| `Table*.kt` | JPA 엔티티 | 비즈니스 규칙(§9에 예외 있음) |
| `*DTO.kt` | 도메인별 Request/Response `data class` 모음 — 요청은 `*Request`, 응답은 `*Response` 접미사, nullable은 명시, 목록 기본값은 `= emptyList()` | 컨트롤러·서비스 파일 안에 DTO 정의 |

**의도적으로 허용하는 것** (2026-10-25 점검에서 규칙을 코드에 맞춰 정정):

- **컨트롤러 레이트리밋** — 한도는 사용자 요청 경로에만 걸어야 한다. 봇(`FeedItemProcessor`)은 `PostService`를 직접
  부르므로, 한도를 Service에 두면 봇까지 막힌다(`PostController` companion 주석). 그래서 글 등록·검색·링크 미리보기·
  업로드 서명은 컨트롤러가 `rateLimitService.consume(...)`을 먼저 부른다. 로그인·가입·비밀번호 재설정처럼 Service 안에서
  판정이 갈리는 한도는 지금처럼 Service에서 건다.
- **다른 도메인 Repository 직접 사용** — 예: `PostService`가 interaction의 `BookmarkRepository`를 쓴다. 금지하는 것은
  **Service끼리의 순환**뿐이다(`servicesMustNotHaveCrossDomainCycles`). 순환이 생기면 `PostResponseAssembler`처럼 어느
  Service도 참조하지 않는 조립기로 뺀다(§7-8).
- **global → domain 3쌍** — `SecurityConfig` → `SessionAuthenticationFilter`(필터 체인 구성),
  `RateLimitService` → `AuthRateLimitRepository`·`TableAuthRateLimit`(범용 카운터가 auth 테이블을 공유). 이 밖은
  `globalMustNotDependOnDomain`이 막는다.
- **패키지 위치 예외** — 외부 연동인 `SupabaseStorageService`가 `global/common`에, FCM의 Controller·Entity·Repository가
  `infra/fcm`에 있다. 옮기지 않는다(새 외부 연동은 `infra/<기술>/`에 둔다).

### 역할 접미사

| 접미사 | 뜻 | 예 |
|---|---|---|
| `*Assembler` | 여러 Repository를 모아 응답 DTO를 조립(트랜잭션은 호출부 것을 씀) | `PostResponseAssembler` |
| `*Processor` | 잡 항목 하나를 독립 트랜잭션으로 처리(같은 클래스 self-invocation을 피하려는 별도 빈) | `FeedItemProcessor` |
| `*Dispatcher` | 커밋 뒤 이벤트를 self-invoke 잡으로 보냄 | `AiJobDispatcher` |
| `*Client` | 외부 HTTP API 호출 | `YoutubeVideoClient` |
| `*Validator` · `*Parser` · `*Extractor` · `*Normalizer` · `*Classifier` · `*Detector` | 한 가지 판단·변환 | `SafeUrlValidator`, `FeedParser` |

상태도 의존성도 없으면 `object`(예: `WeakTitleDetector`, `SecureToken`, `ClientIpResolver`), 다른 빈이나 I/O가 필요하거나
테스트에서 mock해야 하면 `@Component`(예: `FeedParser`, `UrlMetadataExtractor`)로 둔다. I/O 없는 순수한 부분은 별도
함수로 떼어 네트워크 없이 테스트한다(`FeedParser.parse(xml)`, `YoutubeVideoClient`의 `internal fun parseSnippet`).

---

## 4. 응답·에러 계약

### 성공 — `ApiResponse<T>`

```kotlin
return ApiResponse(HttpStatus.OK.value(), "Link preview", postService.previewLink(userId, url))
```

- status는 반드시 `HttpStatus.X.value()` — 숫자 리터럴을 쓰지 않는다(`HttpStatusLiteralTest`가 막는다).
- 쿠키를 붙여야 하는 auth 엔드포인트만 `ResponseEntity<ApiResponse<T>>`를 쓴다(`AuthController`).
- `message`는 FE가 화면에 쓰지 않는다(FE는 `TEXTS`로 문구를 정한다) — 언어가 섞여 있어도 계약 문제는 아니다.

### 실패 — 예외를 던지고 핸들러가 `ErrorResponse(status, code, message)`로 바꾼다

`code`는 대문자 SNAKE_CASE(`FOLDER_NOT_FOUND`)로, FE가 분기에 쓰는 계약이다. `message`는 로그·디버깅용이다.

예외 선택 기준:

| 상황 | 던질 것 |
|---|---|
| 클라이언트 입력이 틀림(빈 값·길이 초과·형식) | `InvalidInputException` → 400 `INVALID_INPUT` |
| 대상이 없음(또는 볼 권한이 없어 없는 것처럼 보여야 함) | `PostNotFoundException` 등 `*NotFoundException` → 404 전용 code |
| 권한 없음(존재를 숨길 필요가 없을 때) | `ForbiddenException` → 403 |
| 동시 요청·중복 | 전용 예외(`DuplicateNicknameException` 등) 또는 전역 409 안전망 |
| **있어서는 안 되는 내부 불변조건 위반**(인증된 principal이 UUID가 아님 등) | `IllegalStateException` → 전용 핸들러 없이 500 |
| ~~`IllegalArgumentException`~~ | **쓰지 않는다** — 핸들러가 404 `NOT_FOUND`로 바꿔 "입력 오류"도 "없음"으로 보인다(§9) |

새 예외 클래스는 `global/exception/`에 만들고 `GlobalExceptionHandler`에 `@ExceptionHandler`를 추가한다 —
`ExceptionHandlerCoverageTest`가 빠뜨림을 잡는다. FE가 code로 분기해야 하면 FE `shared/config/error-code.ts`에도 추가한다.

### code 표

정본은 `global/exception/GlobalExceptionHandler.kt`와 `global/config/security/`다 — 이 표는 그 파생본이다.

| 예외 | HTTP | code |
|---|---|---|
| `PostNotFoundException` | 404 | `POST_NOT_FOUND` |
| `CommentNotFoundException` | 404 | `COMMENT_NOT_FOUND` |
| `BookmarkFolderNotFoundException` | 404 | `FOLDER_NOT_FOUND` |
| `CommentDeletedException` | 409 | `COMMENT_DELETED` |
| `DuplicateMemberException` | 409 | `DUPLICATE_MEMBER` |
| `DuplicateNicknameException` | 409 | `DUPLICATE_NICKNAME` |
| `DuplicateFolderNameException` | 409 | `DUPLICATE_FOLDER_NAME` |
| `SessionMismatchException` | 409 | `SESSION_MISMATCH` |
| `ForbiddenException` | 403 | `FORBIDDEN` |
| `EmailNotVerifiedException` | 403 | `EMAIL_NOT_VERIFIED` |
| `InvalidCredentialsException` | 401 | `INVALID_CREDENTIALS` |
| `InvalidTokenException` | 401 | `INVALID_REFRESH_TOKEN` |
| `InvalidActionTokenException` | 401 | `INVALID_ACTION_TOKEN` |
| `RateLimitExceededException` | 429 | `RATE_LIMIT_EXCEEDED` (+ `Retry-After`) |
| `InvalidInputException` | 400 | `INVALID_INPUT` |
| `InvalidUrlException` | 400 | `INVALID_URL` / `URL_UNRESOLVABLE` / `URL_NOT_ALLOWED` (`e.code`) |
| `UnsupportedImageTypeException` | 400 | `UNSUPPORTED_IMAGE_TYPE` |
| `IllegalArgumentException` | 404 | `NOT_FOUND` (§9 — 정리 예정) |
| Spring `MissingRequestCookieException` | 401 | `MISSING_REFRESH_TOKEN` |
| Jakarta `ConstraintViolationException` · Spring `MethodArgumentNotValidException` | 400 | `INVALID_INPUT` |
| Spring `MethodArgumentTypeMismatchException` | 400 | `INVALID_PATH_VARIABLE` |
| Spring `HttpMessageNotReadableException` | 400 | `INVALID_REQUEST_BODY` |
| Spring `DataIntegrityViolationException` | 409 | `DUPLICATE_RESOURCE` |
| Spring `ConcurrencyFailureException` | 409 | `CONCURRENT_UPDATE` |
| Spring `NoResourceFoundException` | 404 | `NOT_FOUND` |
| Spring `HttpMediaTypeNotAcceptableException` | 406 | (본문 없음) |
| 그 밖의 `Exception` | 500 | `INTERNAL_SERVER_ERROR` |
| 보안 진입점(`CustomAuthenticationEntryPoint`) | 401 | `TOKEN_EXPIRED` / `INVALID_TOKEN` / `NOT_LOGGED_IN` |
| 보안 거부(`CustomAccessDeniedHandler`) | 403 | `ACCESS_DENIED` |

---

## 5. 인증

```kotlin
// 인증 필수 — Security가 보장하므로 non-null. getUserId()가 null이면 "인증 안 됨"이 아니라
// 이미 검증된 principal이 UUID로 안 읽히는 내부 불변조건 위반이라 IllegalStateException(500)이다.
fun previewLink(@RequestParam url: String, authentication: Authentication): ApiResponse<LinkPreviewResponse> {
    val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
    ...
}

// 공개(비로그인 허용) — nullable + Swagger에 공개임을 표시
@SecurityRequirements
@GetMapping("/post")
fun getPosts(authentication: Authentication?, ...): ApiResponse<PostPageResponse> {
    val currentUserId = authentication.getUserId()  // null이면 비로그인
    ...
}
```

- 사용자 id는 항상 `global/common/SecurityUtils.kt`의 `Authentication?.getUserId()`로 꺼낸다. `Authentication.name`·
  `Principal.name`을 직접 파싱하지 않는다. 세션 계열 id가 필요하면 `getSessionFamilyId()`.
- 정석 컨트롤러는 `domain/post/LinkPreviewController.kt` — non-null 인증, `IllegalStateException`, `HttpStatus`,
  컨트롤러 레이트리밋, 근거 주석이 달린 companion 상수가 다 있다.
- **공개 경로는 `SecurityConfig`에 하나씩 나열한다** — `/auth/**` 같은 와일드카드는 `/auth/logout-all`처럼 인증이 필요한
  경로까지 연다. 로그인 여부에 따라 응답이 달라지는 공개 GET(`/post`, `/post/*`, `/post/*/comment`)은 `permitAll()`이
  아니라 `.access { … getAttribute("exception") == null }`을 쓴다 — 토큰 없이 오면 허용하고, 만료·무효 토큰이면 401을
  돌려 FE가 갱신하게 한다(#104, [`plans/2026-10-20-public-get-expired-token-401.md`](plans/2026-10-20-public-get-expired-token-401.md)).
- 세션 토큰·액션 토큰은 `SecureToken`으로 만들고 **DB에는 sha256 해시만** 저장한다. 세션 회전·재사용 탐지는
  `MemberSessionService.rotate`(§7-2).

---

## 6. 트랜잭션

- **새 코드는 클래스에 `@Transactional(readOnly = true)`, 쓰기 메서드에만 `@Transactional`** (`PostService` 형태).
  메서드마다 따로 붙이는 기존 서비스(`CommentService`, `InteractionService` 등)는 그대로 둔다 — 두 스타일이 공존한다.
- 같은 클래스 안에서 `@Transactional` 메서드를 부르면 프록시를 안 거쳐 설정이 적용되지 않는다. 항목마다 독립 트랜잭션이
  필요하면 별도 빈으로 뺀다(`FeedCrawlService` → `FeedItemProcessor`, `AccountPurgeService` → `AccountDeletionService.purge`).
- **rollback-only 함정** — `@Transactional` 메서드에서 예외가 경계를 넘으면, 호출부가 잡고 정상 종료해도 커밋 때
  `UnexpectedRollbackException`(500)이 난다. "없음"을 정상 흐름으로 다뤄야 하면 예외 대신 null을 돌려준다
  (`MemberService.findByEmailOrNull` 주석, [`TRAFFIC-MANAGEMENT.md`](TRAFFIC-MANAGEMENT.md) 시행착오).
- **제약 위반을 그 자리에서 잡으려면 `saveAndFlush`** — `save()`는 INSERT를 커밋 때까지 미룰 수 있어 try 블록 밖에서
  예외가 난다(`PostAIService.processAiJob`의 `saveAndFlush` 주석). 같은 이유로 방금 `save()`한 행을 FK로 가리키는
  네이티브 쿼리 앞에서는 `flush()`가 필요하다(`PostService.saveBookmarkWithFolders`).
- **REQUIRES_NEW** — 호출부가 바로 예외를 던져 롤백돼도 남아야 하는 기록(로그인 실패 카운트, 재사용 탐지 시 계열 폐기)만
  쓴다(`RateLimitService` KDoc). 같은 행에 쓰기 락을 쥔 채 부르면 교착하니 그 경로는 읽기만 한다.
- **AFTER_COMMIT 리스너의 예외는 삼켜진다** — spring-tx 6.2.14 `TransactionSynchronizationUtils.invokeAfterCompletion`이
  `catch (Throwable)` 후 error 로그만 남긴다(2026-10-25 업스트림 v6.2.14 소스로 확인). 그래서 self-invoke 발사가 실패하면
  요청은 성공하고 잡만 사라진다 — 복구 경로는 [`AI-ASYNC-PROCESSING.md`](AI-ASYNC-PROCESSING.md) §5.2.

---

## 7. 패턴 카탈로그

새 코드를 쓰기 전에 같은 문제를 푼 항목을 찾아 그 형태를 따른다. "정석"은 그대로 본뜰 파일이다.

### 7-1. 멱등 insert + 상태 지정(set-state) API

- **언제**: 좋아요·북마크·폴더 소속처럼 "있다/없다"를 바꾸는 쓰기. 연타·여러 탭·재시도에도 결과가 같아야 할 때.
- **정석**: `InteractionService.setPostLike` + `PostReactionRepository.insertIgnoreConflict`
- **모양**: 요청이 원하는 상태(`liked: Boolean`)를 보낸다 → true면 `ON CONFLICT DO NOTHING` insert, false면 일괄 DELETE →
  같은 트랜잭션에서 다시 세어 응답한다.

```kotlin
@Modifying
@Query(
    value = "INSERT INTO post_reactions (user_id, post_id, created_at) " +
        "VALUES (:userId, :postId, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
    nativeQuery = true,
)
fun insertIgnoreConflict(@Param("userId") userId: UUID, @Param("postId") postId: UUID): Int

// Service
if (liked) postReactionRepository.insertIgnoreConflict(userId, postId)
else postReactionRepository.deleteByUserIdAndPostId(userId, postId)
val likeCount = postReactionRepository.countByPostId(postId).toInt() // 같은 트랜잭션 COUNT
```

- **안티패턴**: 토글(현재 상태를 읽고 뒤집기), `exists` 확인 후 분기 저장 — 동시 요청에서 둘 다 통과한다. #92에서 토글을
  모두 없앴다.

### 7-2. 조건부 UPDATE로 선점(claim)

- **언제**: 토큰 소비·탈퇴 퍼지 대상 확보처럼 "먼저 온 한 요청만 이겨야" 할 때.
- **정석**: `MemberSessionRepository.consumeRefreshIfActive`(주석에 READ COMMITTED 전제까지 있음),
  `MemberActionTokenRepository.consumeIfActive`, `MemberRepository.claimForPurge`
- **모양**: `UPDATE … SET consumed_at = :now WHERE id = :id AND consumed_at IS NULL …` → 영향 행 수가 0이면 진 쪽.

```kotlin
if (memberActionTokenRepository.consumeIfActive(token.id, Instant.now()) == 0) {
    throw InvalidActionTokenException("...")
}
```

- **안티패턴**: 읽고 → 조건 확인 → `save()`. 격리 수준을 올리면 이 패턴이 직렬화 오류(500)로 바뀐다.

### 7-3. Upsert (`ON CONFLICT DO UPDATE`)

- **언제**: 같은 키로 여러 번 들어오는 기록(FCM 토큰, 조회 기록, 링크 프리뷰 캐시, 레이트리밋 카운터).
- **정석**: `FcmTokenRepository.upsertToken`(조회 후 분기 저장이 왜 StaleObjectState를 내는지 주석),
  `PostViewRepository.upsertView`, `AuthRateLimitRepository.incrementHit`
- **원자적 카운터**는 `UPDATE … SET view_count = COALESCE(view_count, 0) + 1`(`PostRepository.incrementViewCount`).
  좋아요·북마크·댓글 수는 저장하지 않고 매번 센다.

### 7-4. 삭제는 일괄 DELETE

- **언제**: Repository에 삭제 메서드를 만들 때 항상.
- **정석**: 모든 `deleteBy*`가 `@Modifying @Query("DELETE FROM …")` — 지울 게 없으면 예외 없이 0을 돌려준다.
- **강제**: `repositoryDeleteMethodsMustBeBulk`. derived delete는 엔티티를 읽어 하나씩 지우다 동시 삭제와 부딪혀
  `StaleObjectStateException`을 낸다(2026-10-13 좋아요 연타 500).
- **안티패턴**: derived `deleteBy…`(검사가 막는다), 상속받은 `delete(entity)`·`deleteAll(entities)` — 규칙이 못 잡으니
  리뷰에서 본다(남은 곳은 §9). 새 코드는 일괄 DELETE 메서드를 만든다.

### 7-5. 커밋 뒤 비동기 잡 (self-invoke)

- **언제**: 오래 걸리는 후처리(AI 분석, 알림·링크 프리뷰)를 요청 응답과 분리할 때.
- **정석**: `PostService.createPost`(이벤트 발행) → `PostAIService.handlePostCreatedEvent`(AFTER_COMMIT, 디스패치만) →
  `AiJobDispatcher` → `LambdaHandler`의 `"ai-analysis"` 분기 → `PostAIService.processAiJob`
- **모양**: 이벤트 DTO는 그 도메인 `*DTO.kt`에(`PostCreatedEvent`), payload는 `{linksphereJob, event}`.
  리스너는 디스패치만 하고 즉시 리턴한다. 처리부는 대상이 이미 지워졌을 수 있다고 보고 `?: return`.
- 새 잡을 추가할 때: `infra/aws/<이름>JobDispatcher` + `LambdaHandler`의 `when`에 분기 + `handle<이름>Job` +
  이 문서 §1 표.
- **안티패턴**: 리스너에서 직접 무거운 처리, `@Async`로 요청 스레드 밖에 던지기(Lambda가 응답 뒤 얼어붙는다). `@Async`는
  잡 내부의 병렬화(`GeminiService.analyzeContentAsync`)에만 쓴다.

### 7-6. 커밋 뒤 동기 정리 (`afterCommit`)

- **언제**: 롤백되면 하면 안 되는 외부 부수효과(스토리지 파일 삭제)를 같은 요청 안에서 할 때.
- **정석**: `CommentService.deleteComment`의 `TransactionSynchronizationManager.registerSynchronization { afterCommit }`
- 삭제 실패는 로그만 남긴다 — 남은 파일은 고아 이미지 GC(§7-7)가 회수한다.
- **안티패턴**: 트랜잭션 안에서 바로 외부 삭제(롤백돼도 파일은 이미 없다). 같은 블록을 메서드마다 복사하는 것도 피한다
  — 지금 3곳에 반복돼 있다(남은 것).

### 7-7. 배치 잡 형태

- **언제**: EventBridge가 주기적으로 부르는 정리·수집 잡.
- **정석**: `AccountPurgeService.purgeExpired`(`FeedCrawlService`, `OrphanImageGcService`도 같은 형태)
- **모양**: 대상 id를 한 번에 조회 → 90초 데드라인(Lambda 120초 안) → 항목마다 별도 빈의 `@Transactional` 메서드를
  `runCatching`으로 호출해 한 건 실패가 나머지를 막지 않게 → 남은 건 다음 실행으로 → 요약 로그(`purged=…, failed=…`).

```kotlin
for (id in ids) {
    if (System.currentTimeMillis() > deadline) { logger.warn("... 다음 실행으로 미룸"); break }
    runCatching { accountDeletionService.purge(id, cutoff) }
        .onSuccess { claimed -> if (claimed) purged++ else skipped++ }
        .onFailure { e -> failed++; logger.error("[AccountPurge] 퍼지 실패 - memberId: $id", e) }
}
```

- **안티패턴**: 전체를 한 트랜잭션으로 처리(한 건 실패가 전부를 롤백), 마감 없이 끝까지 처리(Lambda 120초 초과로 잘림).

### 7-8. N+1 없는 목록 조립

- **언제**: 목록 응답에 작성자·좋아요·북마크·댓글 수처럼 다른 테이블 정보를 붙일 때.
- **정석**: `PostResponseAssembler.buildResponsesFromPosts`
- **모양**: id를 모은다 → `findAllBy…In(ids)` 한 번씩 → `associate`/`groupingBy`로 Map → 한 번에 조립. 개수는 GROUP BY
  projection(`CommentRepository`의 `PostCommentCount`)이 가장 싸다.
- 그 밖: `@EntityGraph`·`JOIN FETCH` + 별도 `countQuery`(`CommentRepository`), `default_batch_fetch_size: 100`.
- 조립기를 Service 밖 클래스로 두면 Service 순환도 피한다(KDoc 참고).
- **안티패턴**: 목록을 돌며 항목마다 `findById`·`countBy…` 호출. 단건 상세(`convertToResponse`)는 여러 번 조회해도 된다.

### 7-9. 페이징

- **언제**: 클라이언트가 `page`·`size`를 보내는 목록 API.
- **정석**: `Paging.pageRequest(page, size)` — size를 1~50으로 묶는다(무제한 size 요청 방어). offset 페이징만 있다.
- 정렬 동점은 `createdAt desc, id desc`로 끊는다(`PostRepositoryImpl`).
- **안티패턴**: `PageRequest.of(page, size)`를 직접 호출(size 상한 없음), 정렬 키 하나만 써 페이지 경계에서 중복·누락.

### 7-10. 비공개 글 404 게이트

- **언제**: 글이나 그 글에 딸린 것(댓글·좋아요·북마크)을 읽거나 쓰는 모든 경로.
- **정석**: `InteractionService.assertPostVisible` — `post.isPrivate && post.userId != userId`면 **403이 아니라
  `PostNotFoundException`(404)** 을 던져 존재 자체를 숨긴다. 댓글·북마크·조회 경로가 같은 판정을 쓴다.
- **안티패턴**: `ForbiddenException`(403)으로 막기 — "그 글이 있다"는 사실이 새어 나간다.

### 7-11. 외부 클라이언트

- **정석**: `infra/youtube/YoutubeVideoClient.kt`
- **모양**: `RestClient` + `JdkClientHttpRequestFactory`로 **connect·read 타임아웃을 명시**한다 → 실패는 warn 로그 후
  null 반환 → 호출부가 폴백한다. 키 설정은 `@Value("\${키:}")` 기본값으로 비어 있어도 부팅되게 한다. AWS SDK 클라이언트는
  SnapStart 때문에 `by lazy`(`MailService`). 응답 파싱은 `internal` 순수 함수로 떼어 테스트한다.
- **안티패턴**: 기본 생성자 `RestTemplate()` — 타임아웃이 없다(spring-web 6.2.14 `SimpleClientHttpRequestFactory`는
  timeout이 -1이면 설정하지 않음, 2026-10-25 업스트림 소스로 확인). `SupabaseStorageService`가 이것으로 업로드 서명
  요청이 끝없이 멈췄다 — 지금은 팩토리에 타임아웃을 건 `RestTemplate`이다(§9).

### 7-12. 상수와 설정

- **언제**: 한도·기간·크기처럼 바꿀 수 있는 숫자를 코드에 둘 때.
- **정석**: 서비스 `companion object`의 `private const val` + **왜 그 값인지 주석**
  (`CommentService.MAX_COMMENT_CONTENT_BYTES` — WAF 8KB 벽과 FE 상수와의 동기화 이유).
- 외부 키·URL만 `@Value`로 주입한다. `@ConfigurationProperties`는 쓰지 않는다.
- 다른 클래스가 참조해야 하는 값만 공개 상수로 둔다(`AccountDeletionService.GRACE_PERIOD`).
- **안티패턴**: 근거 주석 없는 매직 넘버, 같은 값을 여러 파일에 따로 선언(지금 90초 마감이 3곳에 있다 — 남은 것).

### 7-13. 엔티티 PK와 시간

- **언제**: 새 `Table*` 엔티티를 만들 때.
- **새 엔티티는 앱에서 만든 UUID**: `val id: UUID = UUID.randomUUID()`(`TableBookmarkFolder`). `@GeneratedValue`와 섞지
  않는다 — `TableMember`·`TablePost`는 `@GeneratedValue` + nullable id라 곳곳에 `id!!`가 있다(바꾸지 않음).
- 복합 키는 `@IdClass`(`TableBookmark`).
- 연관관계는 `LAZY`, 대부분 FK는 원시 UUID 컬럼으로 둔다.
- **안티패턴**: `@GeneratedValue`와 `= UUID.randomUUID()` 기본값을 함께 쓰기(옛 `/new-domain` 템플릿이 그랬다).

---

## 8. 테스트 패턴

| 종류 | 형태 | 정석 |
|---|---|---|
| Service 단위 | `@ExtendWith(MockitoExtension::class)` + `@Mock` + `@InjectMocks` (MockK·AssertJ는 쓰지 않음) | `domain/interaction/InteractionServiceTest.kt` |
| Controller | `@WebMvcTest(excludeFilters = [SecurityFilterChain…])` + `@MockitoBean` + `@WithMockUser(username = <UUID 문자열>)` + `csrf()` | `domain/interaction/InteractionControllerTest.kt` |
| 실제 Postgres | `@PostgresIntegrationTest`(`@DataJpaTest` + Testcontainers `pgvector:pg17` + 운영 덤프 `schema.sql` + `@Tag("integration")`) | `support/PostgresIntegrationTest.kt` |
| 커밋 동작·동시성 | 위 + `@Import(Service)` + `@Transactional(propagation = NOT_SUPPORTED)` | `domain/auth/MemberSessionServiceIntegrationTest.kt` |
| 순수 함수 | 일반 클래스에서 직접 호출 | `domain/post/WeakTitleDetectorTest.kt` |
| 아키텍처 | ArchUnit `@AnalyzeClasses` + `@ArchTest` | `architecture/AppWideArchitectureRulesTest.kt` |

- 테스트 이름은 백틱 한국어 문장이다.
- `./gradlew test`는 `integration` 태그를 빼고 돈다. `./gradlew integrationTest`(Docker 필요)는 PR CI(`ci.yml`)에서만 돈다.
- 쿼리·제약·트랜잭션 경계가 걸린 버그는 Mockito로 재현되지 않는다 — 실제 Postgres 테스트로 재현한다.

---

## 9. 알려진 예외 — 지금 코드가 규칙과 어긋나는 곳

아래는 본뜨지 않는다. 정리 PR이 병합될 때마다 이 표에서 지운다(2026-10-25 점검 기준).

| 항목 | 어디 | 정리 계획 |
|---|---|---|
| `IllegalArgumentException` 17곳 → 404 `NOT_FOUND` | 빈 댓글(`CommentService.createComment` 등)이 400이 아니라 404, `InteractionService.assertCommentLikeable`이 `CommentNotFoundException` 대신 IAE, 회원 없음 불변조건 9곳 | 에러 코드 정리 PR — 입력 오류는 `InvalidInputException`, 불변조건은 `IllegalStateException`, IAE 핸들러는 400 안전망으로 |
| 닉네임 수정 중복이 `DUPLICATE_MEMBER` | `MemberService.updateAccount`(가입은 `DUPLICATE_NICKNAME`) | 위와 같음 |
| `Principal.name`을 문자열로 넘겨 서비스에서 `UUID.fromString` | `AuthController` 5곳 → `AuthService`·`AccountDeletionService` | 사용자 추출 통일 PR |
| `@AuthenticationPrincipal String?` + 자체 파싱 | `CommentController` | 위와 같음 |
| 인증 필수인데 `Authentication?` 17곳 | `InteractionController`(12)·`BookmarkFolderController`(5) | 위와 같음 |
| `ResponseEntity<Void>` 반환, 401 직접 반환 | `FcmTokenController` | 위와 같음 |
| 외부 클라이언트가 RestClient가 아니라 `RestTemplate` | `SupabaseStorageService` — 타임아웃(연결 3초·읽기 10초)은 설정돼 있다 | 바꾸지 않음 — 전환하면 호출부 3곳과 DELETE+본문 동작까지 바뀌어 회귀 범위가 크다 |
| 엔티티 안의 표시 규칙 | `TableMember.isWithdrawn`·`publicNickname`·`publicImage`, `TableLinkPreview.toMetadata` | 바꾸지 않음(기록만) |
| 상속받은 `delete(entity)` 3곳 | `PostService.deletePost`, `CommentService.deleteComment`, `BookmarkFolderService.deleteFolder` | 바꾸지 않음 — 409 `CONCURRENT_UPDATE` 안전망에 기댄다 |

검증 중인 의심(재현되면 수정, 아니면 "남은 것"으로): 가입·재설정 메일이 토큰 커밋 전에 나감, `FcmService`가 바깥
트랜잭션을 rollback-only로 만들 가능성, 폴더 이름 동시 중복이 `DUPLICATE_RESOURCE`로 나감, 상세 조회 응답의 조회수가
증가 전 값, 댓글 `updatedAt` 미갱신, 가입 제약 위반 catch가 `save()` 지연 때문에 헛돎.

---

## 10. 체크리스트

### 새 엔드포인트

- [ ] 컨트롤러는 인증 추출(§5) → 필요하면 레이트리밋 → Service 위임 → `ApiResponse(HttpStatus.X.value(), …)`
- [ ] 인증 필수면 `Authentication`(non-null), 공개면 `Authentication?` + `@SecurityRequirements` + `SecurityConfig` 경로 추가
- [ ] `@Operation(summary, description = "실패: 401 X · 429 Y")` (`SwaggerDocsCoverageTest`)
- [ ] `@WebMvcTest` 컨트롤러 테스트, Service 단위 테스트
- [ ] README `## API 엔드포인트`, FE 코드 생성(`pnpm codegen:fetch && pnpm codegen`)

### 새 도메인

- [ ] `domain/<단수 소문자>/`에 평면 구조: `Table*` · `*Repository` · `*DTO` · `*Service` · `*Controller` (`/new-domain`)
- [ ] 엔티티 PK는 앱에서 만든 UUID(§7-13), 새 테이블 SQL은 `src/main/resources/sql/`, 통합 테스트용 `schema.sql` 갱신
- [ ] 다른 도메인 Service를 참조해 순환이 생기면 조립기로 뺀다(§3)

### 새 예외

- [ ] `global/exception/<이름>Exception.kt` + `GlobalExceptionHandler`에 `@ExceptionHandler`(`ExceptionHandlerCoverageTest`)
- [ ] §4 code 표, FE가 분기하면 FE `error-code.ts`

### 새 배치 잡

- [ ] §7-7 형태(데드라인 + 항목별 `runCatching` + 요약 로그), 항목 처리는 별도 빈의 `@Transactional`
- [ ] `LambdaHandler` 잡 분기 + §1 표, EventBridge 대상 추가는 [`DEPLOY.md`](DEPLOY.md)

### 새 외부 클라이언트

- [ ] `infra/<기술>/`에 `*Client`, `RestClient` + 명시 타임아웃 + 실패 시 null(§7-11)
- [ ] 키는 `@Value("\${…:}")` 기본값, 파싱은 `internal` 순수 함수 + 단위 테스트

---

## 남은 것

이번 점검에서 찾았지만 통일·수정하지 않기로 한 것(되돌릴 근거가 생기면 다시 본다):

- **방식 혼재**: 응답 `message` 언어(영어·한국어), 실제 HTTP 201은 `signup`만(나머지는 HTTP 200 + 본문 201),
  DTO 이름 어순(`PostCreateRequest` vs `CreateFolderRequest`), 엔티티 ID 생성 4종·시간 타입(`LocalDateTime`/`Instant`),
  개수 세는 방식 3종, 커밋 뒤 처리 방식 4종(AFTER_COMMIT 리스너 · 인라인 `afterCommit` — `CommentService`에 3회 반복 ·
  트랜잭션 밖 바로 디스패치 · 커밋 전 동기 메일 발송, 마지막은 §9 검증 대상), `@Valid`는 Auth·FCM 컨트롤러만,
  Page 응답 DTO 수동 복제, 로거 선언 변형, 중복 상수(90초 마감 ×3, 이메일 정규화 ×4).
- **검증하지 않은 위험 후보**: Lambda 응답 헤더가 이름당 첫 값만 남음(`LambdaHandler`의 응답 직렬화 — 여러
  `Set-Cookie`에서 문제), Lambda 안에서 Tomcat 기동 여부, `findFirstByIsBotTrue`의 `@Query` 단건 조회(봇이 둘이면 실패),
  SSRF 검증과 실제 연결 사이의 DNS 재해석, `deploy.yml`은 `integrationTest`를 돌리지 않음.
- **정리 후보**: 쓰는 엔드포인트가 없는 `MultipartRequestParser`, 쓰지 않는 의존성 `spring-boot-starter-oauth2-client`와
  `jwt.secret` 키, 쓰지 않는 Repository 메서드(`PostRepository.findByCategoriesSlug` 등 4개), HTTP로 도달할 수 없는
  `CommentService.createComment`의 `parentId` 분기, 파일명 `PostAiService.kt`와 클래스명 `PostAIService` 불일치.
