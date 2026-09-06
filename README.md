# 🔗 Link Sphere Backend

링크를 저장하고 관리하는 웹 서비스의 백엔드 API 서버입니다.
게시글 작성 시 **Gemini AI**가 자동으로 요약과 태그를 생성하고, 크롤링한
제목·설명이 빈약할 때는 내용을 분석해 대신 채워줍니다.

---

## 기술 스택

| 구분           | 기술                                              |
| -------------- | ------------------------------------------------- |
| **Language**   | Kotlin 2.1.0                                      |
| **Framework**  | Spring Boot 3.5.8                                 |
| **JDK**        | Java 17                                           |
| **Build Tool** | Gradle 8.14.3 (Kotlin DSL) + Shadow JAR           |
| **Database**   | Supabase (PostgreSQL)                             |
| **ORM**        | Spring Data JPA / Hibernate                       |
| **Auth**       | JWT (jjwt 0.12.5) + Spring Security               |
| **AI**         | Google Gemini API (gemini-2.5-flash)              |
| **Push**       | Firebase Cloud Messaging (firebase-admin 9.4.2)  |
| **Storage**    | Supabase Storage (이미지 업로드)                  |
| **API Docs**   | SpringDoc OpenAPI (Swagger UI)                    |
| **Infra**      | AWS Lambda (SnapStart) + CRaC                     |
| **기타**       | Jsoup (HTML 파싱), Jackson, Spring Boot Actuator  |

---

## 프로젝트 구조

```
src/main/kotlin/com/example/linksphere/
├── LambdaHandler.kt                     # AWS Lambda 진입점 (MockMvc 기반)
├── LinkSphereBeApplication.kt           # 메인 애플리케이션
├── domain/
│   ├── auth/                            # 인증 도메인
│   │   ├── AuthController.kt            # 회원가입, 로그인, 토큰 갱신, 로그아웃, 내 정보
│   │   ├── AuthDTO.kt
│   │   ├── AuthService.kt
│   │   └── jwt/
│   │       ├── JwtTokenProvider.kt
│   │       └── JwtAuthenticationFilter.kt
│   ├── member/                          # 회원 도메인
│   │   ├── TableMember.kt
│   │   ├── MemberRepository.kt
│   │   └── MemberService.kt
│   ├── post/                            # 게시글 도메인
│   │   ├── PostController.kt            # 게시글 CRUD + 비공개 토글
│   │   ├── PostDTO.kt
│   │   ├── PostRepository.kt
│   │   ├── PostRepositoryCustom.kt      # 커스텀 쿼리 인터페이스
│   │   ├── PostRepositoryImpl.kt        # QueryDSL/JPQL 검색·필터 구현
│   │   ├── PostService.kt
│   │   ├── PostAiService.kt             # AI 분석 비동기 처리
│   │   ├── TablePost.kt
│   │   └── UrlMetadataExtractor.kt      # URL 크롤링 / YouTube oEmbed
│   ├── comment/                         # 댓글 도메인
│   │   ├── CommentController.kt         # 댓글·답글 CRUD (이미지 포함)
│   │   ├── CommentDTO.kt
│   │   ├── CommentRepository.kt
│   │   ├── CommentService.kt            # FCM 알림 트리거 포함
│   │   └── TableComment.kt
│   ├── interaction/                     # 좋아요·북마크(폴더 다중 소속 포함) 도메인
│   │   ├── InteractionController.kt     # 좋아요 + 북마크 토글 + 폴더 소속 추가/제거
│   │   ├── InteractionService.kt
│   │   ├── BookmarkFolderController.kt  # 폴더 CRUD·순서·폴더별 게시글 조회
│   │   ├── BookmarkFolderService.kt
│   │   ├── BookmarkFolderDTO.kt
│   │   ├── BookmarkRepository.kt
│   │   ├── BookmarkRepositoryCustom.kt
│   │   ├── BookmarkRepositoryImpl.kt    # 폴더 필터 EXISTS/NOT EXISTS 세미조인
│   │   ├── BookmarkFolderRepository.kt
│   │   ├── BookmarkFolderItemRepository.kt  # bookmark_folder_items (N:M 소속)
│   │   ├── PostReactionRepository.kt
│   │   ├── CommentReactionRepository.kt
│   │   ├── TableBookmark.kt
│   │   ├── TableBookmarkFolder.kt
│   │   ├── TableBookmarkFolderItem.kt
│   │   ├── TablePostReaction.kt
│   │   └── TableCommentReaction.kt
│   ├── category/                        # 카테고리 도메인
│   │   ├── CategoryController.kt
│   │   ├── CategoryDTO.kt
│   │   ├── CategoryRepository.kt
│   │   ├── CategoryService.kt
│   │   └── TableCategory.kt
│   └── feed/                            # RSS 피드 자동 수집 (봇 계정, 컨트롤러 없음)
│       ├── FeedCrawlService.kt          # Stage A: 피드 fetch → 후보 URL 5건씩 self-invoke
│       ├── FeedItemProcessor.kt         # Stage B: claim → createPost (항목당 독립 트랜잭션)
│       ├── FeedParser.kt                # Jsoup xmlParser로 RSS 2.0 / Atom 파싱
│       ├── FeedUrlNormalizer.kt         # 중복 판정용 URL 정규화 (dedupe 키 전용)
│       ├── FeedSourceRepository.kt / FeedItemRepository.kt
│       └── TableFeedSource.kt / TableFeedItem.kt
├── global/
│   ├── common/
│   │   ├── ApiResponse.kt               # 공통 응답 래퍼
│   │   ├── ErrorResponse.kt
│   │   ├── SecurityUtils.kt             # Authentication?.getUserId() 확장 함수
│   │   └── SupabaseStorageService.kt    # Supabase 이미지 업로드
│   ├── config/
│   │   ├── AsyncConfig.kt               # 비동기 스레드풀 설정
│   │   ├── SecurityConfig.kt            # Spring Security & CORS 설정
│   │   ├── SwaggerConfig.kt
│   │   └── security/
│   │       ├── CustomAccessDeniedHandler.kt
│   │       └── CustomAuthenticationEntryPoint.kt
│   └── exception/
│       ├── GlobalExceptionHandler.kt
│       ├── DuplicateMemberException.kt
│       ├── ForbiddenException.kt
│       ├── InvalidCredentialsException.kt
│       ├── InvalidTokenException.kt
│       └── PostNotFoundException.kt
└── infra/
    ├── ai/
    │   ├── GeminiService.kt             # Gemini AI 콘텐츠 분석
    │   └── dto/GeminiDtos.kt
    └── fcm/
        ├── FcmConfig.kt
        ├── FcmService.kt
        ├── FcmNotificationService.kt
        ├── FcmTokenController.kt        # FCM 토큰 등록/해제
        ├── FcmTokenDTO.kt
        ├── FcmTokenRepository.kt
        ├── FcmTokenService.kt
        └── TableFcmToken.kt
```

---

## API 엔드포인트

### 🔐 Auth (`/auth`)

| Method | Endpoint        | 설명                    | 인증 |
| ------ | --------------- | ----------------------- | ---- |
| `POST` | `/auth/signup`  | 회원가입                | ❌   |
| `POST` | `/auth/login`   | 로그인                  | ❌   |
| `POST` | `/auth/refresh` | Access Token 갱신       | ❌   |
| `POST` | `/auth/logout`  | 로그아웃 (쿠키 삭제)    | ❌   |
| `GET`  | `/auth/account` | 내 계정 정보 조회       | ✅   |

### 📝 Post (`/post`)

| Method   | Endpoint                  | 설명                           | 인증 |
| -------- | ------------------------- | ------------------------------ | ---- |
| `POST`   | `/post`                   | 게시글 생성 (AI 분석 포함)     | ✅   |
| `GET`    | `/post`                   | 게시글 목록 조회 (검색·필터)   | ✅   |
| `GET`    | `/post/{id}`              | 게시글 상세 조회               | ✅   |
| `PATCH`  | `/post/{id}`              | 게시글 수정                    | ✅   |
| `PATCH`  | `/post/{id}/visibility`   | 게시글 공개/비공개 토글        | ✅   |
| `DELETE` | `/post/{id}`              | 게시글 삭제                    | ✅   |

**게시글 목록 쿼리 파라미터**: `category`, `keyword`, `nickname`, `tags[]`, `isPrivate` 등

### 💬 Comment (`/post/{postId}/comment`, `/comment/{id}`)

| Method   | Endpoint                                      | 설명                    | 인증 |
| -------- | --------------------------------------------- | ----------------------- | ---- |
| `GET`    | `/post/{postId}/comment`                      | 댓글 목록 조회          | ✅   |
| `POST`   | `/post/{postId}/comment`                      | 댓글 작성 (이미지 포함) | ✅   |
| `POST`   | `/comment/{commentId}/reply`                  | 답글 작성 (이미지 포함) | ✅   |
| `PATCH`  | `/comment/{commentId}`                        | 댓글/답글 수정          | ✅   |
| `DELETE` | `/comment/{commentId}`                        | 댓글/답글 삭제          | ✅   |

### ❤️ Interaction

| Method   | Endpoint                              | 설명                                     | 인증 |
| -------- | -------------------------------------- | ---------------------------------------- | ---- |
| `POST`   | `/post/{postId}/like`                  | 게시글 좋아요 토글                       | ✅   |
| `POST`   | `/comment/{commentId}/like`            | 댓글 좋아요 토글                         | ✅   |
| `POST`   | `/post/{postId}/bookmark`              | 게시글 북마크 토글 (해제 시 소속도 전부 삭제) | ✅   |
| `POST`   | `/bookmark/{postId}/folders/{folderId}` | 폴더에 추가 (북마크 없으면 자동 생성)    | ✅   |
| `DELETE` | `/bookmark/{postId}/folders/{folderId}` | 그 폴더에서만 제거 (북마크는 유지)       | ✅   |
| `DELETE` | `/bookmark/{postId}/folders`           | 폴더 소속 전체 해제 (→ 미분류)           | ✅   |
| `POST`   | `/bookmark/batch/folders/{folderId}/add`    | 여러 게시글을 한 폴더에 일괄 추가 (북마크 없으면 자동 생성) | ✅   |
| `POST`   | `/bookmark/batch/folders/{folderId}/remove` | 여러 게시글을 한 폴더에서 일괄 제거      | ✅   |

북마크 하나가 **여러 폴더에 동시에 소속**될 수 있다(N:M). 위 단건 세 엔드포인트는 모두
멱등 — 이미 그 상태여도 200을 반환하며 404를 던지지 않는다. 자세한 폴더 모델은 아래
Bookmark Folder 섹션 참고.

### 📁 Bookmark Folder (`/bookmark/folders`)

| Method   | Endpoint                          | 설명                                             | 인증 |
| -------- | ---------------------------------- | ------------------------------------------------ | ---- |
| `GET`    | `/bookmark/folders`                | 내 폴더 목록 (폴더별 소속 수 + 미분류 수)         | ✅   |
| `POST`   | `/bookmark/folders`                | 폴더 생성                                         | ✅   |
| `PATCH`  | `/bookmark/folders/{folderId}`     | 폴더 이름 수정                                    | ✅   |
| `PATCH`  | `/bookmark/folders/reorder`        | 폴더 순서 일괄 재정렬 (본인 폴더 ID 전체 필요)    | ✅   |
| `DELETE` | `/bookmark/folders/{folderId}`     | 폴더 삭제 (그 폴더에만 있던 북마크만 미분류로)    | ✅   |
| `GET`    | `/bookmark/folders/{folderKey}/posts` | 폴더별 게시글 조회. `folderKey`: `all` \| `uncategorized` \| 폴더 UUID | ✅   |

**미분류** = 어느 폴더에도 소속되지 않은 상태(소속 row 0개), `folder_id IS NULL`이 아니다.
**전체**(`all`)는 소속 폴더 수와 무관하게 북마크 하나당 한 번만 나온다(EXISTS 세미조인으로
중복 방지).

### 📁 Category (`/common/category-option`)

| Method | Endpoint                         | 설명               | 인증 |
| ------ | -------------------------------- | ------------------ | ---- |
| `GET`  | `/common/category-option`        | 전체 카테고리 목록 | ❌   |
| `GET`  | `/common/category-option/{slug}` | 카테고리 상세 조회 | ❌   |

### 🔔 FCM Token (`/fcm`)

| Method   | Endpoint     | 설명                | 인증 |
| -------- | ------------ | ------------------- | ---- |
| `POST`   | `/fcm/token` | FCM 토큰 등록       | ✅   |
| `DELETE` | `/fcm/token` | FCM 토큰 해제       | ✅   |

### 📚 Swagger UI

서버 실행 후 아래 주소에서 API 문서를 확인할 수 있습니다:

```
http://localhost:8080/swagger-ui/index.html
```

---

## 시작하기

### 사전 요구사항

- **JDK 17** 이상
- **Supabase (PostgreSQL)** 데이터베이스

### 1. 설정 파일 생성

`src/main/resources/application-secret.yml` 파일을 생성하고 아래 내용을 본인 환경에 맞게 수정합니다:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://<HOST>:<PORT>/<DATABASE>
    username: <USERNAME>
    password: <PASSWORD>

gemini:
  api:
    key: <YOUR_GEMINI_API_KEY>

jwt:
  secret: <YOUR_JWT_SECRET_KEY>

supabase:
  url: https://<PROJECT>.supabase.co
  key: <SUPABASE_SERVICE_ROLE_KEY>
  bucket: <BUCKET_NAME>
```

> ⚠️ 이 파일은 `.gitignore`에 등록되어 있으므로 git에 커밋되지 않습니다.

FCM을 사용하려면 `src/main/resources/firebase-service-account.json` 파일도 추가해야 합니다.

### 2. 실행

```bash
./gradlew bootRun
```

서버가 **`http://localhost:8080`** 에서 시작됩니다.

### 3. 빌드

```bash
./gradlew build
```

---

## 🚀 배포 (Deployment)

이 프로젝트는 **AWS Lambda SnapStart** 기반으로 배포됩니다.

- **GitHub Actions**: CI/CD 자동화 워크플로우
- **AWS Lambda (SnapStart)**: Shadow JAR 기반 서버리스 실행 (도쿄 리전, arm64 / 2048MB)
- **AWS S3**: Lambda 배포 JAR 저장소 (`deployments/` 30일 만료 수명 주기)
- **Amazon EventBridge**: 5분 간격 워밍 핑(콜드스타트 완화) + 4일 1회 RSS 피드 자동
  수집 트리거(`domain/feed/`, `docs/DEPLOY.md` 8장). 설계·시행착오·프로덕션 검증
  기록은 [**docs/RSS-FEED-BOT.md**](./docs/RSS-FEED-BOT.md) 참고
- **Amazon CloudFront**: `/api/*` → Lambda, 그 외 → S3(FE). FE와 같은 오리진
  - SPA 클라이언트 라우팅 폴백은 CloudFront Function(FE 저장소 `infra/cloudfront-functions/`)이
    담당하며 S3 비헤이비어에만 연결되어 있다. **배포 레벨 `CustomErrorResponses`에 403/404를
    다시 추가하지 말 것** — 오리진 구분 없이 걸려서 이 API가 반환하는 403/404까지 index.html로
    가려버린 적이 있다(2026-07-28). 자세한 내용은 FE 저장소
    `docs/SYSTEM-ARCHITECTURE.md`의 "SPA 라우팅 폴백" 절 참고
- **Supabase**: 클라우드 PostgreSQL 데이터베이스 (도쿄 리전)

> App Runner로 운영했던 이전 배포 방식은 [**docs/DEPLOY-WHEN-APP-RUNNER.md**](./docs/DEPLOY-WHEN-APP-RUNNER.md)를 참고하세요.

### 배포 프로세스 요약

1. **GitHub Push**: `main` 브랜치에 코드가 푸시됩니다.
2. **GitHub Actions**: `./gradlew shadowJar`로 fat JAR를 빌드하고 **AWS S3**에 업로드합니다.
3. **Lambda 코드 업데이트**: S3의 새 JAR를 참조하도록 Lambda 함수를 업데이트합니다.
4. **버전 발행**: `publish-version`으로 **SnapStart 스냅샷**을 생성합니다.
5. **alias 업데이트**: `prod` alias가 새 버전을 가리키도록 교체합니다.

자세한 배포 과정은 [**docs/DEPLOY.md**](./docs/DEPLOY.md) 문서를 참고해주세요.

### Lambda 핵심 구조

Lambda 환경에서는 Tomcat 소켓 문제를 피하기 위해 **MockMvc**로 `DispatcherServlet`을 직접 호출합니다:

```
API 요청 → CloudFront(/api/*) → Lambda Function URL(prod alias)
  → LambdaHandler.handleRequest()
    → MockMvc.perform()
      → Spring DispatcherServlet → 응답
```

### 콜드스타트 대응

SnapStart 체크포인트 **이전**(`companion object init`)에 읽기 전용 엔드포인트로 워밍업 요청을
흘려보내, `DispatcherServlet` 초기화·Security 필터 체인·Hibernate 쿼리플랜·HikariCP 커넥션
확보를 스냅샷에 포함시킵니다. 동일 조건 비교에서 **콜드 첫 요청 2,210ms → 1,046ms(53% 감소)**.

> ⚠️ 이 워밍업은 **Lambda Web Adapter 레이어가 없는 상태**를 전제로 합니다. 레이어를 되돌리면
> 워밍업이 깨져 502가 발생합니다. 측정 데이터·장애 사후 분석·측정 방법론은
> [**docs/PERFORMANCE.md**](./docs/PERFORMANCE.md), 롤백 절차는
> [**docs/LAMBDA-CONFIG-ROLLBACK.md**](./docs/LAMBDA-CONFIG-ROLLBACK.md)를 참고하세요.

---

## 환경 설정

| 항목                   | 설명                 | 파일                     |
| ---------------------- | -------------------- | ------------------------ |
| 서버 포트, JPA 설정 등 | 공통 설정            | `application.yml`        |
| DB 접속 정보, API 키   | 민감 정보 (git 제외) | `application-secret.yml` |
| Firebase 서비스 계정   | FCM 인증 (git 제외)  | `firebase-service-account.json` |

---

## 문서

**프로젝트 전반**

- [`CHANGELOG.md`](CHANGELOG.md) — 버전별 변경 사항 (Keep a Changelog)

**서사형** — "왜 만들었고 어떻게 동작하는지"

- [`docs/RSS-FEED-BOT.md`](docs/RSS-FEED-BOT.md) — RSS 피드 자동 수집 봇: 발견(RSS)·등록(크롤링)·분석(AI) 3단계 구조
- [`docs/AI-ASYNC-PROCESSING.md`](docs/AI-ASYNC-PROCESSING.md) — 게시글 AI 분석을 비동기(Lambda self-invoke)로 뺀 이유와 구조
- [`docs/CI-CHECK-GATE.md`](docs/CI-CHECK-GATE.md) — ktlint·테스트를 PR·배포 파이프라인의 실제 게이트로 정비한 과정
- [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) — Lambda 콜드스타트 원인 분석과 최적화 근거

**절차** — "이럴 땐 이렇게 한다"

- [`docs/DEPLOY.md`](docs/DEPLOY.md) — 배포 인프라 설정·변경, EventBridge 룰 조작, GitHub Actions 배포 흐름
- [`docs/LAMBDA-CONFIG-ROLLBACK.md`](docs/LAMBDA-CONFIG-ROLLBACK.md) — `prod` alias를 안전 버전으로 롤백해야 할 때

**레퍼런스** — "지금 값이 뭔가"

- [`docs/VERSION-COMPATIBILITY.md`](docs/VERSION-COMPATIBILITY.md) — BE·FE 버전 호환 매트릭스: 어느 FE 버전이 어느 BE 버전을 요구하는지

**보관** — 더 이상 갱신하지 않음

- [`docs/HISTORY.md`](docs/HISTORY.md) — 2026-08-01부터 갱신 중단. 이후 이력은 FE 레포에서 통합 관리
- [`docs/DEPLOY-WHEN-APP-RUNNER.md`](docs/DEPLOY-WHEN-APP-RUNNER.md) — App Runner로 운영하던 이전 배포 방식

> 문서 분류 기준과 작성 규칙은 [`.claude/CLAUDE.md`](.claude/CLAUDE.md)의 "문서 파일 위치"·"서사형 작업 문서 형식" 절 참고.

---

## Frontend

프론트엔드 프로젝트: [link-sphere_FE_NEW](https://github.com/BAECHAN/link-sphere_FE_NEW)

- CORS 허용 Origin: `http://localhost:31119`, `https://localhost:31119`, AWS CloudFront 도메인
