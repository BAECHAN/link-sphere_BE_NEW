# Changelog

이 프로젝트(Link-Sphere BE)의 주요 변경 사항을 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 표기는 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 사용합니다.

## [Unreleased]

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

[Unreleased]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/releases/tag/v0.1.0
