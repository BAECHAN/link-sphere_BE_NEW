# Changelog

이 프로젝트(Link-Sphere BE)의 주요 변경 사항을 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 표기는 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 사용합니다.

## [Unreleased]

### Added

- Gemini 호출에 모델 폴백 체인 도입 — 무료 등급의 `gemini-2.5-flash`는 일일 20건(RPD)
  제한이라 링크 10건만 등록·수정해도 소진된다. 상위 모델이 429(쿼터 초과)·5xx(과부하)·
  404(모델 지원 종료)를 내면 같은 요청을 다음 모델(`gemini-3.1-flash-lite`, 일일 500건)로
  즉시 재시도하도록 변경. 대기(백오프)는 두지 않는다(RPM 초과는 1분을 기다려야 회복되는데
  Lambda 예산이 30초). 설정 키가 `gemini.api.model`(단수) → `gemini.api.models`(복수,
  쉼표 구분)로 바뀌었다. (`GeminiService`, `application.yml`)

- 게시글 수정(`PATCH /post/{id}`)에서 URL 변경 지원 — `PostUpdateRequest`에 `url` 추가.
  URL이 실제로 바뀐 경우에만 생성 때와 동일하게 재크롤링(`UrlMetadataExtractor`)해
  제목·설명·이미지·태그를 새 링크 기준으로 교체하고, `aiSummary`를 비운 뒤
  `PostCreatedEvent`를 발행해 AI 요약·태그를 다시 생성한다. 이때 제목은 사용자가 입력한
  값 대신 새 링크에서 크롤링한 제목으로 덮어쓴다. (`url`이 없거나 기존과 같으면 기존 동작 유지)
- `PostUpdateRequest.title`을 선택값으로 변경 — 비워서 보내면 새 링크에서 가져온 제목을 쓰고,
  URL 변경이 없으면 기존 제목을 유지한다(빈 제목 저장 방지). 카테고리를 빈 배열로 보내면
  기존 AI 자동 분류가 새 링크 기준으로 카테고리를 다시 채운다.
- 카테고리를 지정하지 않은 게시글을 AI가 자동 분류 — 태그를 카테고리 마스터의
  name/slug와 매칭(비용 0)하고, 실패 시 Gemini 의미 분류로 폴백해 `post.categories`를
  채운다. 사용자가 직접 선택한 글은 건드리지 않음. "태그는 보이는데 카테고리 필터에
  안 나오는" 불일치(이슈 #9) 해소. (`PostCategoryClassifier`, `PostAIService`)
- 폴더 목록 응답(`GET /bookmark/folders`)에 미분류(`folder_id IS NULL`) 북마크 수
  `uncategorizedCount` 추가 — FE에서 '미분류'·'전체' 개수를 표시할 수 있도록 지원.

### Changed

- 폴더 목록 응답 형태를 배열(`List<FolderResponse>`)에서 래퍼 객체
  `{ folders, uncategorizedCount }`(`FolderListResponse`)로 변경.
- 비로그인(익명) 사용자에게 콘텐츠 조회 GET 엔드포인트를 공개 —
  `GET /post`, `GET /post/{id}`, `GET /post/{id}/comment`, `GET /post/ai-events`를
  `permitAll`에 추가 (HTTP 메서드 지정 방식이라 글·댓글 작성/수정/삭제 등 쓰기
  요청은 인증 유지. 카테고리 조회는 기존 `/common/**` 공개 범위에 이미 포함)

### Fixed

- 댓글 조회(`GET /post/{postId}/comment`)가 비로그인 시 500/404로 실패하던 문제 수정 —
  Security가 익명 사용자 principal로 주입하는 `"anonymousUser"` 문자열을 UUID로 파싱하려다
  예외가 발생하던 것을 null(비로그인)로 처리
- 게시글 검색(피드 `/post`, 북마크 `/bookmark/folders/{folderKey}/posts`)을
  개선 — 검색어를 공백으로 토큰 분리해 각 토큰을 OR 매칭하도록 변경
  (단어 사이에 다른 글자가 끼어도 검색됨, 한국어 붙여쓰기/띄어쓰기 양방향 대응)
- 검색 결과를 관련도순으로 정렬 — 제목(3) > 태그(2) > 설명(1) 가중치 +
  제목 완전일치/prefix 보너스. 북마크는 기본(latest) 정렬일 때만 관련도순 적용
  (title/views/oldest 명시 선택 시 기존 동작 유지)
- 피드 카테고리 필터를 INNER JOIN → `EXISTS` 서브쿼리로 변경
  (중복 행 제거용 `DISTINCT` 불필요, 관련도 정렬과 호환)

## [0.2.0] - 2026-07-11

### Added

- 북마크 폴더 게시글 조회(`/bookmark/folders/{folderKey}/posts`)에 `search`
  파라미터 추가 — 현재 폴더 범위 내에서 제목·설명·태그를 부분 검색
  (피드 검색과 동일한 공백 무시 LIKE 매칭, 미지정 시 기존 동작 유지)

## [0.1.0] - 2026-06-28

### Added

- **북마크 폴더 관리 API** — 북마크를 폴더 단위로 분류·탐색
  - 폴더 CRUD: 생성 / 목록 조회(북마크 수 포함) / 이름 수정 / 삭제
    (`/bookmark/folders`)
  - 폴더 삭제 시 안의 북마크는 미분류(`folder_id = NULL`)로 자동 이동
    (FK `ON DELETE SET NULL`)
  - 폴더 순서 재정렬 API (`PATCH /bookmark/folders/reorder`)
  - 폴더별 게시글 조회 (`/bookmark/folders/{folderKey}/posts`,
    folderKey = `all` / `uncategorized` / 폴더 UUID)
    - 정렬 4종: 최신(latest) / 오래된(oldest) / 제목(title) / 조회수(views)
  - 단건 북마크 폴더 이동 (`PATCH /bookmark/{postId}/folder`)
  - 다중 선택 일괄 이동·삭제 (`POST /bookmark/batch/move`, `/batch/delete`)
  - 동일 사용자 내 폴더 이름 중복 금지 (409 `DUPLICATE_FOLDER_NAME`)
- `PostResponse.userInteractions.bookmarkFolderId` 필드 추가
  (게시글이 속한 북마크 폴더 ID, 비로그인 시 `null`)

### Changed

- `PostService`: 게시글 목록 변환 로직을 `buildResponsesFromPosts`로 분리해
  다른 도메인(북마크 폴더)에서 재사용 가능하도록 변경

### Migration

- `sql/create_bookmark_folders.sql` 실행 필요
  - `bookmark_folders` 테이블 생성 (`user_id` + `name` UNIQUE)
  - `bookmarks` 테이블에 `folder_id` 컬럼 및 FK(`ON DELETE SET NULL`) 추가

[Unreleased]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/releases/tag/v0.1.0
