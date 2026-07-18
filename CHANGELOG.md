# Changelog

이 프로젝트(Link-Sphere BE)의 주요 변경 사항을 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 표기는 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 사용합니다.

## [Unreleased]

### Changed

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
