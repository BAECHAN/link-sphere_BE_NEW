# 🔗 Link Sphere Backend

링크를 저장하고 관리하는 웹 서비스의 백엔드 API 서버입니다.  
게시글 작성 시 **Gemini AI**가 자동으로 요약과 태그를 생성합니다.

---

## 기술 스택

| 구분           | 기술                                 |
| -------------- | ------------------------------------ |
| **Language**   | Kotlin 2.1.0                         |
| **Framework**  | Spring Boot 3.5.8                    |
| **JDK**        | Java 17                              |
| **Build Tool** | Gradle 8.14.3 (Kotlin DSL)           |
| **Database**   | PostgreSQL (Supabase)                |
| **ORM**        | Spring Data JPA / Hibernate          |
| **Auth**       | JWT (jjwt 0.12.5) + Spring Security  |
| **AI**         | Google Gemini API (gemini-2.5-flash) |
| **API Docs**   | SpringDoc OpenAPI (Swagger UI)       |
| **기타**       | Jsoup (HTML 파싱), Lombok, Jackson   |

---

## 프로젝트 구조

```
src/main/kotlin/com/example/linksphere/
├── LinkSphereBeApplication.kt       # 메인 애플리케이션
├── domain/
│   ├── auth/                        # 인증 도메인
│   │   ├── AuthController.kt        # 회원가입, 로그인, 토큰 갱신, 로그아웃
│   │   ├── AuthDTO.kt               # 요청/응답 DTO
│   │   ├── AuthService.kt           # 인증 비즈니스 로직
│   │   └── jwt/
│   │       ├── JwtTokenProvider.kt   # JWT 토큰 생성/검증
│   │       └── JwtAuthenticationFilter.kt
│   ├── member/                      # 회원 도메인
│   │   ├── TableMember.kt           # 회원 Entity
│   │   ├── MemberRepository.kt
│   │   └── MemberService.kt
│   ├── post/                        # 게시글 도메인
│   │   ├── PostController.kt        # 게시글 CRUD
│   │   ├── PostDTO.kt
│   │   ├── PostRepository.kt
│   │   ├── PostService.kt
│   │   └── TablePost.kt
│   └── category/                    # 카테고리 도메인
│       ├── CategoryController.kt    # 카테고리 조회
│       ├── CategoryDTO.kt
│       ├── CategoryRepository.kt
│       ├── CategoryService.kt
│       └── TableCategory.kt
├── global/config/
│   └── SecurityConfig.kt            # Spring Security & CORS 설정
└── infra/ai/
    ├── GeminiService.kt             # Gemini AI 콘텐츠 분석
    └── dto/GeminiDtos.kt
```

---

## API 엔드포인트

### 🔐 Auth (`/auth`)

| Method | Endpoint        | 설명                 | 인증 |
| ------ | --------------- | -------------------- | ---- |
| `POST` | `/auth/signup`  | 회원가입             | ❌   |
| `POST` | `/auth/login`   | 로그인               | ❌   |
| `POST` | `/auth/refresh` | Access Token 갱신    | ❌   |
| `POST` | `/auth/logout`  | 로그아웃 (쿠키 삭제) | ❌   |

### 📝 Post (`/post`)

| Method | Endpoint                | 설명                       | 인증 |
| ------ | ----------------------- | -------------------------- | ---- |
| `POST` | `/post`                 | 게시글 생성 (AI 분석 포함) | ✅   |
| `GET`  | `/post`                 | 전체 게시글 조회           | ✅   |
| `GET`  | `/post?category={slug}` | 카테고리별 게시글 조회     | ✅   |
| `GET`  | `/post/{id}`            | 게시글 상세 조회           | ✅   |

### 📁 Category (`/common/category-option`)

| Method | Endpoint                         | 설명               | 인증 |
| ------ | -------------------------------- | ------------------ | ---- |
| `GET`  | `/common/category-option`        | 전체 카테고리 목록 | ❌   |
| `GET`  | `/common/category-option/{slug}` | 카테고리 상세 조회 | ❌   |

### 📚 Swagger UI

서버 실행 후 아래 주소에서 API 문서를 확인할 수 있습니다:

```
http://localhost:51119/swagger-ui/index.html
```

---

## 시작하기

### 사전 요구사항

- **JDK 17** 이상
- **PostgreSQL** 데이터베이스 (또는 Supabase)

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
```

> ⚠️ 이 파일은 `.gitignore`에 등록되어 있으므로 git에 커밋되지 않습니다.

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

## 환경 설정

| 항목                   | 설명                 | 파일                     |
| ---------------------- | -------------------- | ------------------------ |
| 서버 포트, JPA 설정 등 | 공통 설정            | `application.yml`        |
| DB 접속 정보, API 키   | 민감 정보 (git 제외) | `application-secret.yml` |

---

## Frontend

프론트엔드 프로젝트: [link-sphere_FE_NEW](https://github.com/BAECHAN/link-sphere_FE_NEW)

- CORS 허용 Origin: `http://localhost:31119`, `https://localhost:31119`
