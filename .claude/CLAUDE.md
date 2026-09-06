# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:

- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.
- When asserting how the existing system currently works ("we follow pattern X", "this mirrors
  framework Y"), trace the actual code path first — don't infer architecture from a
  similarly-shaped utility or an external framework's mechanism. State plainly when a claim is
  traced vs. inferred.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:

- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:

- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

## 5. Impact Check Before Changes

**Enumerate what can break before writing code. Both directions: the new work, and what already works.**

Before implementing, check and report:

- **CRUD failure points** — walk 등록(create) / 수정(update) / 읽기(read) / 삭제(delete) for the
  data this change touches. Name what breaks at each: missing rows, duplicate/idempotency,
  ownership & visibility checks, cascade behavior, count/pagination correctness, concurrent requests.
- **Regression on existing features** — list every existing behavior that could break, with the file
  that owns it. Include: shared queries and cache/invalidation paths, derived counts, existing tests
  that encode the old contract, docs and user-facing text that assert the old behavior, and dead or
  unused code paths that still compile against it.

Report both lists before the first edit, not after. If the change alters a data contract
(schema, DTO, API shape), say explicitly what the deploy order is and what breaks in between.

## 6. Precedent Before Invention

**Find how this codebase already solves it. Copy that shape.**

Before designing anything new:

- Search for an existing feature in the same class of problem, and read it.
- Name the precedent by file path before you write code.
- Follow its shape: layering, naming, cache/rollback strategy, error ownership.
- Deviate only for a stated reason - and state the reason.

This is about reusing the established *shape*, not about extending existing functions.
Writing a new hook/util that follows the precedent is the expected outcome.

The test: "Which existing file did I model this on?" should always have an answer.

## 7. User-Facing Tradeoffs Need Sign-Off

**A technical constraint's side effect can still be a UX decision. Don't absorb it silently.**

Some decisions look purely technical ("we can't bind X to history because of Y") but have a
consequence the user actually experiences ("so pressing back will navigate the page instead of
just closing the dialog"). That consequence is a UX call, not a technical inevitability — even
though it followed logically from the constraint. Surface it and ask before treating it as
settled.

- Don't cite research/precedent more strongly than it supports. If a source covers a related but
  different scenario, say so plainly ("X source is about Y, not exactly this case") — don't imply
  it validates the current decision.
- When challenged on a past decision, re-verify the reasoning before defending it. Check whether
  the original claim actually holds up instead of restating it with more confidence.
- If you can't point to the moment the user was asked and agreed, you decided for them — flag it
  and ask, even after the fact.

The test: could the user tell, from what you told them, that this was a judgment call they didn't
get to weigh in on? If not, you decided for them.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

# Link-Sphere BE — Claude Code Guide

---

## 프로젝트 공통 컨텍스트

- **BE**: Spring Boot + Kotlin, port 8080, context-path `/api`
- **FE**: React + TypeScript + Vite, FSD 아키텍처, port 31119
- **배포**: CloudFront → `/api/*` Lambda(BE), `/*` S3(FE)
- **Lambda**: Function URL 기반, CloudFront 뒤에 배치. arm64 / 2048MB / SnapStart, **레이어 없음**
- **커밋**: 작업 전 `.gitmessage` 파일 먼저 읽고 형식 준수
- **커밋 단위**: 대화 턴(요청)마다 나누지 않고, 논리적으로 완결된 기능·수정 단위로 나눈다.
  같은 기능을 다듬는 과정에서 나온 후속 수정(버그 픽스 포함)은 원래 커밋에 합치고,
  서로 무관한 변경끼리만 별도 커밋으로 분리한다.

---

## Critical Rules

- **Never** `Authentication.name` 직접 파싱 → 항상 `authentication.getUserId()` 확장 함수 사용 (`global/common/SecurityUtils.kt`)
- **Never** Controller에서 비즈니스 로직 → 반드시 Service 레이어로 위임
- **Never** Repository에서 직접 예외 throw → Service에서 처리
- **Never** 새 예외 클래스 없이 `IllegalArgumentException` 남용 → 의미 있는 예외 클래스를 `global/exception/`에 추가
- **Never** `GlobalExceptionHandler` 수정 없이 새 예외 클래스 추가 → 핸들러에 `@ExceptionHandler` 반드시 등록
- **Never** Controller에서 직접 HTTP 상태코드 하드코딩 → `HttpStatus.*` 상수 사용
- **Never** Security 인증 없이 사용자 식별 → `Authentication?`이 null이면 인증 안 된 것, 명시적으로 처리
- **Never** 대상 파일 양식 무시하고 코드 생성 → 항상 붙여넣을 파일(및 인접 코드)을 **먼저 읽고** 들여쓰기·네이밍·import 순서·따옴표·주석 밀도·정렬을 그대로 맞춘다. 본인 스타일을 강요하거나 기존 코드를 재포맷하지 않는다
- **Never** Lambda Web Adapter 레이어를 다시 붙이지 않는다 → 2026-07-25 502 장애의 직접 원인이었고, `LambdaHandler.warmUp()`은 이 레이어가 **없는 상태를 전제**로 한다. 붙이면 워밍업이 깨진다 (`docs/PERFORMANCE.md` 5장)
- **Never** 검증 없이 `prod` alias 이동 → 정상 배포는 2026-08-13부터 `deploy.yml`이
  자동으로 처리한다(발행 → 새 버전 직접 5회 연속 호출 → 전부 통과해야 승격, 하나라도
  실패하면 워크플로우가 죽고 `prod`는 이전 버전 유지). 이 규칙은 이제 **롤백 등
  CI를 우회하는 예외적 수동 개입에만** 적용된다 — 그럴 때도 반드시 버전을 **직접
  연속 호출**해 확인한 뒤 옮긴다. 위 장애는 "복원 후 첫 요청은 성공, 이후 실패"
  패턴이라 단발 확인으로는 잡히지 않았다
  ```bash
  aws lambda invoke --function-name link-sphere-api:<버전> --log-type Tail \
    --payload fileb://event.json /tmp/out.json --query 'LogResult' --output text | base64 -d
  ```
- **Never** 인프라·배포·아키텍처 변경 후 문서 갱신 누락 → BE `README.md`·`docs/DEPLOY.md`와 함께 **FE `docs/SYSTEM-ARCHITECTURE.md`** 도 확인한다 (BE 인프라를 서술하고 있어 가장 놓치기 쉽다). 이 규칙은 인프라급이 아닌 **기능 변경**에도 그대로 적용한다 — 반대편 레포의 서사형 문서(`docs/*-BOT.md` 등)가 이번에 바뀐 동작을 서술하고 있으면 같은 턴에 찾아 고친다(2026-09-06, FE 봇 글 숨기기 토글의 저장 방식을 URL→localStorage로 바꿨을 때 BE `docs/RSS-FEED-BOT.md` §3의 Playwright 검증 서술이 어긋난 사례). 문서를 고쳤으면 **최종 보고에 "문서 X를 Y로 갱신함"을 별도 항목으로 명시**한다 — 조용히 고쳐두기만 하면 사용자 입장에서는 확인이 안 된 것과 같다
- **Never** `CommentService.MAX_COMMENT_CONTENT_BYTES`(현재 6,000)를 CloudFront WAF 상태 확인 없이 올리지 않는다 → CloudFront에 붙은 WAF의 `SizeRestrictions_BODY`가 요청 바디 8,192바이트 초과를 차단한다(AWS 기본값, 코드로 추적 안 됨). 2026-09-06 이 값을 완화하려 했으나 대체 크기 제한 룰이 **CloudFront Pro 플랜(월 $15) 전용**이라 Free 플랜인 이 계정에서 못 만들어 원복했다 — 지금도 8KB 벽이 살아있다. 이 상수를 올리려면 먼저 WAF 상태를 확인할 것(자세한 내용·재적용 절차는 FE `docs/DEPLOY.md`의 "CloudFront WAF (수동 관리)" 절, FE `docs/DECISIONS.md` 2026-09-06 항목 참고)
- **Never** 워크트리 없이 코드 수정 → 이 레포는 여러 Claude 세션이 동시에 돈다. 코드를 **수정하는**
  작업(읽기 전용 조사·질문 답변은 예외)을 시작할 때는 항상 `EnterWorktree`로 워크트리를 만들고
  그 안에서 작업한다. 워킹트리 파일과 `.git/index`(스테이징 영역)를 세션끼리 공유하면 서로
  덮어쓰거나 무관한 커밋에 남의 변경이 딸려 들어간다 — 실제로 겪은 사고 경위는 이 규칙을
  도입한 커밋(`2648c7f`) 메시지 참고
- **Never** `git add`/`git rm`으로 변경을 미리 스테이징 → 워크트리를 쓰지 않는 세션이 하나라도
  있으면 위와 같은 인덱스 오염이 재발한다. 커밋은 항상 `git commit -- <경로...>` 로 대상 파일을
  직접 지정한다
- **Never** 워크트리 진입 후 부트스트랩 생략 → `EnterWorktree`로 만든 워크트리는 gitignore된
  설정 파일이 없다. 진입 직후 반드시 실행:
  ```bash
  cp ../../../src/main/resources/application-secret.yml src/main/resources/
  cp ../../../src/main/resources/firebase-service-account.json src/main/resources/
  ```
- **Never** 여러 워크트리에서 동시에 `bootRun` → 포트(8080)와 DB(원격 Postgres, `ddl-auto: none`)
  는 워크트리로 격리되지 않는다. dev 서버는 한 번에 한 워크트리에서만 띄운다
- **Never** `EnterWorktree` 기본값(`fresh` = `origin/main` 기준)을 확인 없이 사용 → 다른 세션이
  로컬 main에만 커밋하고 아직 push하지 않았다면 그 커밋이 빠진 채로 새 워크트리가 갈라진다.
  작업 시작 전 `git log origin/main..main`으로 미푸시 커밋이 있는지 먼저 확인한다
- **Never** 작업 끝난 워크트리를 `keep`으로 방치 → 병합·push까지 끝나면 `ExitWorktree`를
  `action: "remove"`로 정리한다. 세션이 정상 종료되면 harness가 keep/remove를 물어보지만,
  강제 종료·크래시 시엔 이 프롬프트가 안 뜬다(`.claude/worktrees/ci-guardrails/` 잔존 사례로
  확인됨). 새 워크트리를 만들기 전 `git worktree list`로 오래된 워크트리가 남아있는지 먼저
  훑고, 디렉토리는 있는데 목록엔 없는 경우(비정상 종료로 등록이 깨진 경우) `git worktree prune`
  으로 정리한다
- **Never** 새 lint/format 도구의 ignore 패턴을 루트 상대 경로로만 작성 → `.claude/worktrees/`
  같은 중첩 경로가 새서 워크트리 안의 빌드 산출물(`dist/`)이 그대로 린트된다. `.gitignore`에
  있어도 ESLint/Prettier는 자동으로 읽지 않으므로 `dist/**/*`가 아니라 `**/dist/**` 처럼
  `**/` prefix를 붙여야 중첩 경로까지 잡힌다(2026-09-03, FE `pnpm check` 2,370건 중 2,366건이
  이 문제였다)

---

## 패키지 구조

```
src/main/kotlin/com/example/linksphere/
├── domain/                    # 비즈니스 도메인 (평면 구조, 서브패키지 없음)
│   ├── auth/                  # 인증 (AuthController, AuthService, AuthDTO, jwt/)
│   ├── category/              # 카테고리
│   ├── comment/               # 댓글
│   ├── interaction/           # 좋아요·북마크 (InteractionController, InteractionService, ...)
│   ├── member/                # 회원
│   └── post/                  # 게시글
│
├── global/
│   ├── common/                # ApiResponse, ErrorResponse, SecurityUtils, SupabaseStorageService
│   ├── config/                # SecurityConfig, SwaggerConfig, AsyncConfig, security/
│   └── exception/             # 예외 클래스들, GlobalExceptionHandler
│
└── infra/
    ├── ai/                    # GeminiService (AI 요약)
    └── fcm/                   # FCM 푸시 알림
```

**도메인 추가 시**: `domain/<도메인명>/` 디렉토리에 평면 구조로 파일 생성. 서브패키지(api/, service/ 등) 만들지 않는다.

### 패키지 네이밍 원칙

| 위치 | 규칙 | ✅ | ❌ |
| ---- | ---- | -- | -- |
| `domain/` 하위 도메인 | **단수 소문자** | `post/`, `comment/`, `member/` | `posts/`, `Post/`, `post-domain/` |
| 도메인 내 서브패키지 | **최소화, 단일 소문자** (꼭 필요할 때만) | `jwt/`, `log/` | `jwt-util/`, `utils/` |
| `global/` 하위 | **역할 단수 소문자** | `common/`, `config/`, `exception/` | `commons/`, `configs/` |
| `infra/` 하위 | **기술/서비스명 그대로** | `ai/`, `fcm/`, `storage/` | `ai-service/`, `fcmService/` |
| infra 내 서브패키지 | **단일 소문자** | `dto/` | `dtos/`, `DTO/` |

---

## 응답 포맷

### 성공 응답 — `ApiResponse<T>`

```kotlin
// global/common/ApiResponse.kt
data class ApiResponse<T>(
    val status: Int,
    val message: String,
    val data: T,
    val timestamp: String = ...
)

// 사용 예
return ApiResponse(HttpStatus.OK.value(), "북마크 폴더 조회 성공", folderList)
return ApiResponse(HttpStatus.CREATED.value(), "폴더 생성 성공", folder)
```

### 에러 응답 — `ErrorResponse`

```kotlin
// global/common/ErrorResponse.kt
data class ErrorResponse(
    val status: Int,
    val code: String,       // 대문자 SNAKE_CASE (예: FOLDER_NOT_FOUND)
    val message: String,
    val timestamp: String = ...
)
```

---

## 파일 역할 규칙

| 파일             | 역할                    | 규칙                                                                 |
| ---------------- | ----------------------- | -------------------------------------------------------------------- |
| `Table*.kt`      | JPA 엔티티              | `@Entity`, `@Table`, `@Column` — 비즈니스 로직 없음                  |
| `*Repository.kt` | Spring Data JPA         | 쿼리 메서드만, 복잡한 로직은 `*RepositoryImpl` + `*RepositoryCustom` |
| `*Service.kt`    | 비즈니스 로직           | `@Service`, `@Transactional` — 유효성 검사, 예외 throw, 변환         |
| `*Controller.kt` | HTTP 진입점             | `@RestController` — 인증 추출 + Service 위임 + `ApiResponse` 반환만  |
| `*DTO.kt`        | Request/Response 클래스 | `data class` — 도메인별 하나의 파일에 모아서 관리                    |

---

## 인증 처리 패턴

```kotlin
// 인증 필수 엔드포인트
@PostMapping("/bookmark/folders")
fun createFolder(
    @RequestBody request: CreateFolderRequest,
    authentication: Authentication   // nullable 아님 → Security가 보장
): ApiResponse<FolderResponse> {
    val userId = authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
    return ApiResponse(HttpStatus.CREATED.value(), "폴더 생성 성공", service.createFolder(userId, request))
}

// 인증 선택적 엔드포인트 (비로그인도 조회 가능)
@GetMapping("/post")
fun getPosts(
    authentication: Authentication?  // nullable → 비로그인 허용
): ApiResponse<...> {
    val currentUserId = authentication.getUserId()  // null이면 비로그인
    ...
}
```

---

## 예외 처리 패턴

### 1. 예외 클래스 생성 (`global/exception/`)

```kotlin
// global/exception/BookmarkFolderNotFoundException.kt
class BookmarkFolderNotFoundException(folderId: UUID) :
    RuntimeException("Bookmark folder not found: $folderId")
```

### 2. GlobalExceptionHandler에 등록

```kotlin
// global/exception/GlobalExceptionHandler.kt 에 추가
@ExceptionHandler(BookmarkFolderNotFoundException::class)
fun handleBookmarkFolderNotFoundException(e: BookmarkFolderNotFoundException): ResponseEntity<ErrorResponse> {
    val response = ErrorResponse(
        status = HttpStatus.NOT_FOUND.value(),
        code = "FOLDER_NOT_FOUND",
        message = e.message ?: "Folder not found"
    )
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
}
```

### 기존 예외 코드 참조

| 예외                          | HTTP | code                    |
| ----------------------------- | ---- | ----------------------- |
| `PostNotFoundException`       | 404  | `POST_NOT_FOUND`        |
| `ForbiddenException`          | 403  | `FORBIDDEN`             |
| `DuplicateMemberException`    | 409  | `DUPLICATE_MEMBER`      |
| `InvalidCredentialsException` | 401  | `INVALID_CREDENTIALS`   |
| `InvalidTokenException`       | 401  | `INVALID_REFRESH_TOKEN` |

---

## Security — 새 엔드포인트 공개 허용

기본적으로 모든 요청은 인증 필요. 비로그인 허용이 필요한 경우 `SecurityConfig.kt`의 `permitAll()` 목록에 추가:

```kotlin
it.requestMatchers(
    "/auth/**",
    "/common/**",
    "/bookmark/folders/public/**",   // 예시: 공개 폴더 조회
).permitAll()
```

---

## DB / JPA 패턴

### 단일 PK 엔티티

```kotlin
@Entity
@Table(name = "bookmark_folders")
class TableBookmarkFolder(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "created_at", updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
```

### 복합 PK 엔티티 (기존 `TableBookmark` 참조)

```kotlin
@Entity
@IdClass(BookmarkId::class)
class TableBookmark(
    @Id @Column(name = "user_id") val userId: UUID,
    @Id @Column(name = "post_id") val postId: UUID,
    ...
)
```

### Self-referential (부모-자식, 폴더 중첩)

```kotlin
@Column(name = "parent_id", nullable = true)
var parentId: UUID? = null    // null = 루트 폴더
```

---

## Repository 패턴

```kotlin
// 기본 Spring Data JPA
interface BookmarkFolderRepository : JpaRepository<TableBookmarkFolder, UUID> {
    fun findByUserIdOrderBySortOrderAsc(userId: UUID): List<TableBookmarkFolder>
    fun findByUserIdAndParentIdIsNull(userId: UUID): List<TableBookmarkFolder>
    fun existsByIdAndUserId(id: UUID, userId: UUID): Boolean
}

// 복잡한 쿼리가 필요한 경우 → Custom 패턴 사용 (PostRepositoryCustom/Impl 참조)
interface PostRepositoryCustom { ... }
class PostRepositoryImpl : PostRepositoryCustom { ... }
interface PostRepository : JpaRepository<TablePost, UUID>, PostRepositoryCustom
```

---

## DTO 작성 규칙

- 도메인별로 `*DTO.kt` 파일 하나에 모아서 관리
- Request: `*Request` suffix (`CreateFolderRequest`, `UpdateFolderRequest`)
- Response: `*Response` suffix (`FolderResponse`, `FolderListResponse`)
- `data class` 사용, 불변 필드는 `val`, 가변은 `var`

```kotlin
// domain/interaction/BookmarkFolderDTO.kt
data class CreateFolderRequest(
    val name: String,
    val parentId: UUID? = null
)

data class UpdateFolderRequest(
    val name: String
)

data class ReorderFoldersRequest(
    val folderIds: List<UUID>   // 순서대로 정렬된 ID 목록
)

data class FolderResponse(
    val id: UUID,
    val name: String,
    val parentId: UUID?,
    val sortOrder: Int,
    val bookmarkCount: Int,
    val children: List<FolderResponse> = emptyList()
)
```

---

## 개발 커맨드

```bash
./gradlew bootRun          # 로컬 실행 (port 8080)
./gradlew build            # 빌드
./gradlew test             # 테스트 실행
./gradlew ktlintCheck      # 코드 스타일 검사
./gradlew ktlintFormat     # 코드 스타일 자동 수정
```

---

## 체크리스트: 새 도메인 API 추가

- [ ] `Table*.kt` — JPA 엔티티 작성
- [ ] `*Repository.kt` — Spring Data JPA 인터페이스
- [ ] `*DTO.kt` — Request/Response data class
- [ ] `*Service.kt` — `@Service`, `@Transactional` 비즈니스 로직
- [ ] `*Controller.kt` — `@RestController` + `ApiResponse` 반환
- [ ] `global/exception/` — 필요한 예외 클래스 추가
- [ ] `GlobalExceptionHandler.kt` — 예외 핸들러 등록
- [ ] `SecurityConfig.kt` — 공개 허용 엔드포인트 있으면 `permitAll()` 추가

---

## 슬래시 커맨드 (`.claude/commands/`)

| 커맨드        | 사용법                                      | 역할                                                                   |
| ------------- | ------------------------------------------- | ---------------------------------------------------------------------- |
| `/new-domain` | `/new-domain bookmark-folder`               | Entity + Repository + DTO + Service + Controller + Exception 일괄 생성 |
| `/add-api`    | `/add-api interaction batch-move-bookmarks` | 기존 도메인에 API 엔드포인트 추가                                      |

---

## 코드 스타일 레퍼런스

새 도메인 구현 전 아래 파일들을 읽어 스타일을 학습한다. 각 파일이 해당 역할의 정석 패턴이다.

| 역할 | 레퍼런스 파일 | 핵심 패턴 |
| ---- | ------------- | --------- |
| Controller | `domain/comment/CommentController.kt` | `ApiResponse` 래핑, 한글 메시지, `Authentication` 파라미터 주입, `@AuthenticationPrincipal` |
| Service (CRUD) | `domain/comment/CommentService.kt` | `@Transactional`, fail-fast 검증, 배치 조회(N+1 방지), 권한 확인, 소프트 삭제 |
| Service (Toggle) | `domain/interaction/InteractionService.kt` | `exists → delete/save → boolean` 반환 패턴, `when` 표현식 타입 분기 |
| DTO | `domain/comment/CommentDTO.kt` | `data class`, nullable 명시, Request/Response 분리, 기본값(`= emptyList()`) |
| Entity | `domain/comment/TableComment.kt` | UUID PK, `LAZY` loading, `insertable=false` FK, timestamp 자동화 |
| 예외 핸들러 | `global/exception/GlobalExceptionHandler.kt` | `@ExceptionHandler` 등록, `ErrorResponse(status, code, message)` |
| 인증 유틸 | `global/common/SecurityUtils.kt` | `Authentication?.getUserId(): UUID?` 확장 함수 |

---

## 릴리즈노트 (CHANGELOG) 관리

레포 루트 `CHANGELOG.md`로 변경 이력을 관리한다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/) + [SemVer](https://semver.org/lang/ko/), **한글 작성**.

**규칙**
- `feat` / `fix` / `perf` / 동작이 바뀌는 `refactor` 커밋 시 → **`CHANGELOG.md`의 `[Unreleased]` 섹션에 항목 추가**를 같은 커밋에 포함한다.
- 섹션: `Added` / `Changed` / `Fixed` / `Removed`. DB 변경은 `Migration`(실행할 SQL 명시) 섹션 사용.
- `docs` / `style` / `test` / `chore` 등 사용자 영향 없는 변경은 기록하지 않는다.

**항목 포맷** — 한 줄 요약 + 접힌 상세로 훑어볼 수 있게 쓴다.
```markdown
- `post` 게시글 등록 시 북마크 폴더를 함께 지정 가능
  <details><summary>배경·구현</summary>

  지금까지는 등록 후 별도 API를 호출해야 폴더에 담을 수 있었다. `PostService.createPost`
  트랜잭션 안에서 동일한 검증·insert 순서로 처리한다.
  (`PostDTO.PostCreateRequest`, `PostService.createPost`)

  </details>
```
- 요약 줄: `` `스코프` `` + 공백 + 한 줄(72자 이내, 줄바꿈·마침표 없음). 굵게(`**`) 쓰지 않는다.
  스코프는 `post` `comment` `auth` `member` `bookmark` `category` `upload` `infra` 중 하나.
- 상세 블록: `<summary>`는 `배경·구현`으로 통일. `<summary>` 다음과 `</details>` 앞에 빈 줄을
  반드시 넣는다(없으면 GitHub이 안의 마크다운을 파싱하지 않는다). 배경·트레이드오프·영향
  파일 목록을 요약 없이 그대로 적는다 — 짧은 항목은 상세 블록을 생략해도 된다.
- `### Migration`·`### Notes`는 접지 않는다 — 배포 시 반드시 봐야 하는 정보다.

**릴리즈 시점** (버전 확정)
1. `[Unreleased]` 항목들을 새 버전 섹션 `## [X.Y.Z] - YYYY-MM-DD` 으로 승격 (빈 `[Unreleased]` 유지), 하단 compare 링크 갱신 (`https://github.com/BAECHAN/link-sphere_BE_NEW`)
2. API 계약(요청/응답 스펙, 필드 추가·제거, permitAll 등)이 바뀌었다면 `docs/VERSION-COMPATIBILITY.md`에도 상대 레포 최소 버전 행 추가
3. `chore(release): vX.Y.Z` 커밋 → `git push origin main`
4. **태그·GitHub Release는 수동으로 만들지 않는다** — `.github/workflows/release.yml`이 `CHANGELOG.md` push를 감지해 최신 버전 섹션을 파싱, 동명 태그가 없으면 자동으로 태그 생성 + `gh release create`까지 수행한다(이미 있으면 스킵하는 멱등 동작). `git tag`/`gh release create`를 직접 실행할 필요 없음.
- 현재 버전 기준점: `0.1.0` (정식 릴리즈 전 개발 단계 = `0.x`)

## 문서 파일 위치

루트에는 `README.md`·`CHANGELOG.md`만 둔다 — GitHub 생태계에서 관례적으로 루트에 두는
특수 파일(LICENSE·CONTRIBUTING과 같은 급)이고, CHANGELOG는 Keep a Changelog 스펙 자체가
루트 배치를 표준으로 규정한다. 그 외 모든 문서(배포 가이드, 아키텍처, 성능·장애 기록,
버전 호환 매트릭스 등)는 전부 `docs/`에 둔다.

`docs/` 안의 문서는 넷 중 하나다 — **서사형**(설명, 아래 절 참고) / **절차**(how-to·런북,
예: `DEPLOY.md`·`LAMBDA-CONFIG-ROLLBACK.md`) / **레퍼런스**(예: `VERSION-COMPATIBILITY.md`) /
**보관**(archive, 더 이상 갱신하지 않는 과거 기록 — 예: `HISTORY.md`·`DEPLOY-WHEN-APP-RUNNER.md`).
새 문서를 쓰기 전에 어느 종류인지 먼저 정하고, 한 문서에 여러 목적을 섞지 않는다
(*Software Engineering at Google* 10장: "문서는 하나의 목적만 갖고 거기 충실해야 한다 —
API가 한 가지를 잘하듯, 한 문서에 여러 개를 담지 마라"). 전체 목록과 안내는
`docs/README.md`가 랜딩 페이지로 관리한다 — 새 문서를 추가하면 거기와 루트
`README.md`의 `## 문서` 섹션 두 곳 모두에 등록한다.

## 서사형 작업 문서 형식 (`docs/AI-ASYNC-PROCESSING.md`, `docs/RSS-FEED-BOT.md` 등)

"왜 만들었고 어떻게 동작하는지"를 설명하는 서사형 문서(기능 하나를 처음부터
끝까지 기록하는 문서 — CHANGELOG 항목이나 커밋 메시지로는 다 담기 힘든 배경·구조·
디버깅 과정을 남길 때 쓴다)는 아래 순서를 따른다. 기존 `docs/AI-ASYNC-PROCESSING.md`·
`docs/PERFORMANCE.md`가 "문제 → 해결 → 시행착오 → 검증" 뼈대를 각자 암묵적으로
따르고는 있었지만 명문화된 적은 없었다 — `docs/RSS-FEED-BOT.md`(2026-09-03, RSS 피드
봇 문서화 과정에서 확정, 2026-09-04 "문서만 보고 고칠 수 있는가" 리뷰를 거쳐 자기완결성
요건 추가)를 기준 예시로 아래처럼 고정한다.

**원칙**: 좋은 문서 = *독자에게 필요한 지식 − 독자가 이미 가진 지식* (Google 기술 문서
가이드). 잘 아는 사람이 쓴 문서는 모르는 상태를 상상하지 못해 맥락을 건너뛰기 쉽다("지식의
저주") — 그래서 아래 순서는 전제 지식 선언·용어 정의를 구조적으로 강제한다. 단, 이미 다른
곳에 정본이 있는 사실(DDL, 파일 트리, 코드 상수 값 등)은 옮겨적지 않고 **링크로 가리킨다**
— 복제하면 원본이 바뀔 때 문서가 조용히 거짓말하게 된다(SSOT).

모든 문서는 제목 아래 인용구(`>`) 블록으로 아래 네 가지를 번호 없이 먼저 밝힌다 —
Google 기술 문서 가이드의 audience declaration(WHO/WHAT/WHY)에 해당:

- **문서 성격** — 서사형/절차/레퍼런스/보관 중 무엇인지
- **대상 독자** — 예: "이 레포 BE를 처음 보거나 오랜만에 돌아온 개발자"
- **읽고 나면** — 이 문서만 보고 할 수 있게 되는 것 (예: "피드 소스를 추가하거나 버그를
  재현·수정할 수 있다")
- **마지막 검토**: `YYYY-MM-DD` — 문서를 고칠 때마다 갱신한다. 실제로 다시 읽고 고친
  날짜만 적는다(검토하지 않은 문서에 오늘 날짜를 넣지 않는다). 인프라·아키텍처를 바꿨으면
  관련 문서의 검토일도 함께 확인한다(Critical Rules의 "인프라·배포·아키텍처 변경 후
  문서 갱신 누락" 규칙과 같은 취지)

이어서 본문은 아래 순서를 따른다:

1. **쉬운 설명** — 핵심 개념을 이 도메인을 모르는 독자(다른 스택 개발자 등)도
   이해할 수 있는 비유로 먼저 설명. 전문 용어를 그대로 던지지 않는다. **장 끝에 전체
   프로세스 Mermaid 순서도를 반드시 넣는다** — 각 단계가 무엇을 하고 무엇을 주고받는지
   (외부 호출·DB 읽기/쓰기 등)를 노드 라벨에 적어, 글을 안 읽고 그림만 봐도 흐름이 따라와야
   한다. ```mermaid 펜스를 쓴다(GitHub이 도형으로 렌더한다). 한글 라벨에 `·`·`(`·`:`가
   섞이면 노드 텍스트를 큰따옴표로 감싸고, 줄바꿈은 `<br/>`를 쓴다
2. **전제 지식** — 이 문서가 가정하는 지식과 가정하지 않는 지식을 나누고, 가정하지 않는
   부분은 어느 문서를 먼저 보면 되는지 링크한다
3. **사용한 도구·기술** — bullet point로, "기능 자체를 이루는 것"과 "구현·검증
   과정에서 쓴 도구"를 구분해서 나열
4. **왜 만들었나** (문제)
5. **구조/아키텍처** (해결 방향) — 다이어그램·핵심 설계 결정과 그 근거. 이 장의
   다이어그램은 1번 순서도와 성격이 다르면(Lambda 경계·페이로드 등 운영 상세) ASCII로
   남겨도 되지만, 두 그림의 역할 차이를 한 줄로 밝힌다
6. **데이터 모델** — 새 테이블을 만든 기능이면 컬럼별 역할을 표로 설명한다. DDL 원문은
   옮겨적지 않고 정본 SQL 파일을 링크한다
7. **운영 파라미터** — 시간·횟수·건수 등 "언제/몇 개"에 해당하는 값은 표로 정리하고,
   각 값의 실제 위치를 명시한다(코드 상수면 `파일:줄`, 레포 밖에 있으면 그 사실과
   실제 위치 — 예: "AWS 콘솔/CLI, 어느 문서의 몇 장"). 값이 어디 있는지 얼버무리지
   않는다
8. **코드 지도와 자주 하는 수정** — 순서도 단계 ↔ 파일 매핑과 "이렇게 고치려면" 레시피
   표(각 항목에 재배포 필요 여부 명시). `README.md`에 이미 있는 파일 트리는 복제하지 않고,
   README에 없는 축(단계 ↔ 파일 매핑, 수정 레시피)으로 채운다
9. **검증 결과** — 실제로 확인한 수치·로그
10. **시행착오** — 겪은 버그와 디버깅 과정은 **뒤쪽에 배치**. 결과를 다 본 사람을
    위한 부록 성격이지, 첫 진입점이 아니다
11. **남은 것**
12. **용어 사전** — 이 문서에서 처음 나오는 고유 용어를 정의 없이 쓰지 않는다. 이미
    일반적인 개념이면 정의 대신 외부·내부 링크로 대신한다
13. **관련 문서** 링크

문서를 다 쓴 뒤에는 처음부터 훑어 다음을 확인한다 — 사람 리뷰어가 없는 1인 개발 레포에서
Google이 말하는 "독자 리뷰"(도메인을 모르는 사람이 읽어 명확성을 검증하는 것)를 스스로
대행하는 절차다: ① 정의 없이 등장하는 고유 용어가 있는가 ② 이 문서만 보고 값을 바꾸거나
고칠 수 있는가 ③ 다른 곳의 정본과 중복해서 적은 사실이 있는가.

기존 규칙(`## 릴리즈노트 관리`, 이 절 바로 위)이 CHANGELOG의 형식을 규정하는 것과
같은 이유로, 이 문서군도 매번 새로 형식을 고민하지 않도록 여기 고정해둔다.
