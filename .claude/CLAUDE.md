# Link-Sphere BE — Claude Code Guide

---

## 프로젝트 공통 컨텍스트

- **BE**: Spring Boot + Kotlin, port 8080, context-path `/api`
- **FE**: React + TypeScript + Vite, FSD 아키텍처, port 31119
- **배포**: CloudFront → `/api/*` Lambda(BE), `/*` S3(FE)
- **Lambda**: Function URL 기반, CloudFront 뒤에 배치
- **커밋**: 작업 전 `.gitmessage` 파일 먼저 읽고 형식 준수

---

## Critical Rules

- **Never** `Authentication.name` 직접 파싱 → 항상 `authentication.getUserId()` 확장 함수 사용 (`global/common/SecurityUtils.kt`)
- **Never** Controller에서 비즈니스 로직 → 반드시 Service 레이어로 위임
- **Never** Repository에서 직접 예외 throw → Service에서 처리
- **Never** 새 예외 클래스 없이 `IllegalArgumentException` 남용 → 의미 있는 예외 클래스를 `global/exception/`에 추가
- **Never** `GlobalExceptionHandler` 수정 없이 새 예외 클래스 추가 → 핸들러에 `@ExceptionHandler` 반드시 등록
- **Never** Controller에서 직접 HTTP 상태코드 하드코딩 → `HttpStatus.*` 상수 사용
- **Never** Security 인증 없이 사용자 식별 → `Authentication?`이 null이면 인증 안 된 것, 명시적으로 처리

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
