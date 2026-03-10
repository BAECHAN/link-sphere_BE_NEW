# 🔗 Link Sphere Backend

링크를 저장하고 관리하는 웹 서비스의 백엔드 API 서버입니다.
게시글 작성 시 **Gemini AI**가 자동으로 요약과 태그를 생성합니다.

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
│   ├── interaction/                     # 좋아요·북마크 도메인
│   │   ├── InteractionController.kt
│   │   ├── InteractionService.kt
│   │   ├── BookmarkRepository.kt
│   │   ├── ReactionRepository.kt
│   │   ├── TableBookmark.kt
│   │   └── TableReaction.kt
│   └── category/                        # 카테고리 도메인
│       ├── CategoryController.kt
│       ├── CategoryDTO.kt
│       ├── CategoryRepository.kt
│       ├── CategoryService.kt
│       └── TableCategory.kt
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

| Method | Endpoint                       | 설명              | 인증 |
| ------ | ------------------------------ | ----------------- | ---- |
| `POST` | `/post/{postId}/like`          | 게시글 좋아요 토글 | ✅   |
| `POST` | `/comment/{commentId}/like`    | 댓글 좋아요 토글  | ✅   |
| `POST` | `/post/{postId}/bookmark`      | 게시글 북마크 토글 | ✅   |

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
http://localhost:51119/swagger-ui/index.html
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

서버가 **`http://localhost:51119`** 에서 시작됩니다.

### 3. 빌드

```bash
./gradlew build
```

---

## 🚀 배포 (Deployment)

이 프로젝트는 **AWS Lambda SnapStart** 기반으로 배포됩니다.

- **GitHub Actions**: CI/CD 자동화 워크플로우
- **AWS Lambda (SnapStart)**: Shadow JAR 기반 서버리스 실행 (도쿄 리전)
- **AWS S3**: Lambda 배포 JAR 저장소
- **Supabase**: 클라우드 PostgreSQL 데이터베이스 (도쿄 리전)

> App Runner로 운영했던 이전 배포 방식은 [**docs/DEPLOY_WHEN_APP_RUNNER.md**](./docs/DEPLOY_WHEN_APP_RUNNER.md)를 참고하세요.

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
API 요청 → Lambda Function URL
  → LambdaHandler.handleRequest()
    → MockMvc.perform()
      → Spring DispatcherServlet → 응답
```

---

## 환경 설정

| 항목                   | 설명                 | 파일                     |
| ---------------------- | -------------------- | ------------------------ |
| 서버 포트, JPA 설정 등 | 공통 설정            | `application.yml`        |
| DB 접속 정보, API 키   | 민감 정보 (git 제외) | `application-secret.yml` |
| Firebase 서비스 계정   | FCM 인증 (git 제외)  | `firebase-service-account.json` |

---

## Frontend

프론트엔드 프로젝트: [link-sphere_FE_NEW](https://github.com/BAECHAN/link-sphere_FE_NEW)

- CORS 허용 Origin: `http://localhost:31119`, `https://localhost:31119`, AWS CloudFront 도메인
