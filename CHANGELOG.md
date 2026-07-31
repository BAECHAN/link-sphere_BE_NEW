# Changelog

이 프로젝트(Link-Sphere BE)의 주요 변경 사항을 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 표기는 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 사용합니다.

## [Unreleased]

### Changed

- **게시글 등록(POST /post) 시 AI 분석을 별도 Lambda 비동기 호출로 위임** — 기존엔
  크롤링 이후 Gemini 요약·카테고리 분류가 끝날 때까지 응답을 미룬 채 동기로 기다렸다
  (Lambda가 `handleRequest()` 반환 후 컨테이너를 얼려버려 `@Async` 백그라운드 스레드로는
  AI 처리를 이어갈 수 없었기 때문에 과거 이 방식으로 되돌렸던 것). 크롤링·AI 처리 합산
  시간이 CloudFront origin timeout을 넘기면 클라이언트는 504를 받지만 서버는 계속
  실행돼 게시글은 이미 커밋된 채 응답만 유실되는 문제가 있었다. 이제 커밋 후 AI 작업을
  Lambda 자기 자신에 대한 비동기(Event) 호출로 위임해 완전히 독립된 실행 환경에서
  처리한다 — POST /post 응답은 크롤링만 끝나면 바로 나가고, AI 결과는 백그라운드에서
  반영된 뒤 `GET /post/{id}` 재조회 시 확인된다. 요약과 카테고리 분류도 순차 대신
  병렬로 실행한다(카테고리 분류 입력은 AI 태그 대신 크롤링 시점 기존 태그만 사용하도록
  변경 — 병렬화를 위해 순차 의존을 끊음). self-invoke 시 qualifier를 `prod`로 명시해
  SnapStart 최적화(스냅샷 복원)를 그대로 받는다 — qualifier 없이 호출하면 AWS가
  기본값인 `$LATEST`로 보내는데, SnapStart는 `ApplyOn=PublishedVersions`라 `$LATEST`엔
  적용되지 않아 매번 완전 콜드스타트를 물게 된다(EventBridge 워밍 핑과 동일한 함정,
  `docs/DEPLOY.md` 6장). (`AiJobDispatcher`, `LambdaHandler.handleAiJob`,
  `PostAIService.processAiJob`, `GeminiService.analyzeContentAsync`,
  `PostCategoryClassifier.classifyAsync`)
- Gemini `RestClient` 타임아웃 명시(커넥트 5초/응답 45초) — 기존엔 타임아웃 미설정으로
  무제한 대기가 가능해 CloudFront timeout의 주요 원인 중 하나였다. (`GeminiService`)

### Fixed

- **AI 분석 중인 게시글을 삭제하면 예외가 조용히 삼켜지거나(구조 변경 전) 불필요한
  Lambda 재시도가 발생하는(방금 반영한 비동기 위임 구조) 문제** — `postRepository.save()`는
  UPDATE를 즉시 실행하지 않고 트랜잭션 커밋 시점까지 지연시키는데, 그 사이 다른 요청이
  같은 post를 삭제하면 커밋 시 Hibernate가 "0 rows updated"를 감지해
  `ObjectOptimisticLockingFailureException`을 던진다. 이 예외가 try/catch 블록 바깥(커밋
  경계)에서 발생해 잡히지 않았다. `saveAndFlush()`로 바꿔 UPDATE를 catch 블록 안에서
  즉시 실행시키고, 이 예외를 "삭제로 인한 정상적인 레이스"로 명시적으로 구분해 INFO
  레벨로 로깅하도록 수정(AI 분석 실패로 오인해 재시도하지 않는다). 실제 프로덕션
  CloudWatch 로그에서 재현·확인. (`PostAIService.processAiJob`)
- **유튜브 등 리다이렉트 링크 등록 시 크롤링·AI 요약 실패** — SSRF 방지를 위해 리다이렉트를
  홉마다 직접 따라가도록 바꾼 뒤(0.4.0), 중간 리다이렉트 응답의 Content-Type이
  `text/html`이 아니면(예: youtu.be의 303 응답은 `application/binary`) Jsoup이 상태
  코드를 확인하기도 전에 `UnsupportedMimeTypeException`을 던져 크롤링 전체가 실패하고
  있었다. 크롤링 실패 시 `pageContent`가 null이 되어 `aiStatus`가 `PENDING`으로 올라가지
  않아 AI 분석 이벤트 자체가 발행되지 않았다. `safeConnect()`의 Jsoup 커넥션에
  `ignoreContentType(true)`를 추가해 해결. (`UrlMetadataExtractor.safeConnect`)

## [0.5.0] - 2026-07-31

### Added

- **북마크 다중 폴더 소속 지원** — 북마크 하나를 여러 폴더에 동시에 저장할 수 있도록
  `bookmark_folder_items` 소속 테이블을 신설. 신규 엔드포인트 3종: `POST/DELETE
  /bookmark/{postId}/folders/{folderId}`(폴더에 추가/그 폴더에서만 제거), `DELETE
  /bookmark/{postId}/folders`(소속 전체 해제 → 미분류). 추가/제거 모두 멱등(같은 요청
  반복해도 200) — 없는 소속을 제거해도 404를 던지지 않는다. 폴더 추가 시 북마크가 없으면
  자동 생성한다("북마크 보장 + 소속 보장"). 배치 엔드포인트도 다중 폴더 대응:
  `POST /bookmark/batch/folders/{folderId}/add`, `POST
  /bookmark/batch/folders/{folderId}/remove` 신규 추가. (`TableBookmarkFolderItem`,
  `BookmarkFolderItemRepository`, `InteractionService.addBookmarkFolder` 등,
  `BookmarkFolderService.batchAddBookmarksToFolder` 등)
- `PostUserInteractions.bookmarkFolderIds: List<UUID>` — 게시글이 속한 모든 폴더 ID
  목록. 기존 단일 `bookmarkFolderId` 필드를 대체.

### Changed

- **폴더 삭제 시 다른 폴더에도 있는 북마크는 그대로 유지** — 기존엔 폴더를 삭제하면 안의
  북마크가 전부 미분류로 이동했다(DB `ON DELETE SET NULL`). 이제는 삭제된 그 폴더의
  소속만 없어지고, 다른 폴더에도 속해 있던 북마크는 그 폴더에 그대로 남는다. 미분류가
  되는 건 그 폴더가 마지막 소속이었던 경우뿐. (`BookmarkFolderService.deleteFolder`)
- `BookmarkFolderService.getFolders` — 폴더 개수만큼 COUNT 쿼리를 날리던 N+1을 그룹
  카운트 쿼리 1회로 교체. (`BookmarkFolderItemRepository.countByUserIdGroupByFolderId`)
- `BookmarkRepositoryImpl.findBookmarkedPosts`의 폴더 필터를 `bookmarks.folder_id`
  단일 컬럼 비교에서 `bookmark_folder_items` 상관 EXISTS/NOT EXISTS 세미조인으로 교체
  — 북마크 하나가 여러 폴더에 속해도 `전체`/검색 결과에 중복 없이 정확히 한 번만 나온다.

### Removed

- `PATCH /bookmark/{postId}/folder`(단건 이동), `POST /bookmark/batch/move`(일괄 이동)
  — "폴더 하나로 교체"라는 단일 소속 시대의 API로, 다중 소속에서는 의미가 없어 폴더별
  추가/제거 엔드포인트로 대체됨.
- `bookmarks.folder_id` 컬럼(마이그레이션 Phase C에서 제거, 아래 참고),
  `PostUserInteractions.bookmarkFolderId`(단일), `BookmarkNotFoundException`
  — `moveBookmark`가 유일한 사용처였음.

### Migration

`ddl-auto: none`이라 아래 SQL을 수동 실행해야 한다.

1. **배포 전** — `src/main/resources/sql/create_bookmark_folder_items.sql` 실행.
   파일 헤더의 `\d bookmarks` 사전 확인(복합 PK/UNIQUE, `post_id` FK 존재 여부)을 먼저
   수행할 것. 기존 `bookmarks.folder_id` 값을 `bookmark_folder_items`로 백필하고,
   실행 후 출력되는 검증 SELECT 두 값이 일치하는지 확인한다. 구버전 BE와 공존 가능
   (가산적 변경).
2. **BE 배포 직후** — 1번의 백필 INSERT를 한 번 더 실행 (`ON CONFLICT DO NOTHING`이라
   안전) — 백필~컷오버 사이 구버전이 만든 지정을 회수하기 위함.
3. **배포 후 수동 검증 4항목** (BE 테스트에 DB가 없어 자동화 불가, SQL 파일 헤더 참고):
   - 2개 폴더에 든 글이 `GET /bookmark/folders/all/posts`에 한 번만 나오고
     `totalElements` 일치
   - `GET /bookmark/folders/{uuid}/posts?search=...`가 200 (관련도 정렬에 DISTINCT가
     안 끼었음을 증명 — 끼면 Postgres가 `SELECT DISTINCT ... ORDER BY` 오류로 500)
   - 다른 폴더에도 있는 글이 담긴 폴더를 삭제해도 `bookmarks` 수는 불변
   - 2개 폴더에 든 글을 북마크 해제하면 관련 `bookmark_folder_items`가 0건
4. **검증 완료 후에만** — `src/main/resources/sql/drop_bookmarks_folder_id.sql` 실행
   (파괴적, `bookmarks.folder_id` 컬럼 제거).

### Fixed

- **비공개 게시글·댓글 조회 인가 누락 수정** — 상세 조회(`GET /post/{id}`)와 댓글 조회
  (`GET /post/{id}/comment`)에 목록·북마크 조회에만 있던 가시성 검증이 빠져 있어, 비로그인
  사용자도 게시글 UUID만 알면 남의 비공개 글과 댓글을 그대로 읽을 수 있었다. 소유자가
  아니면 404로 응답하도록 수정. (`PostService.getPostById`, `CommentService.getComments`)
- **비공개 게시글 좋아요·북마크·댓글·답글 인가 누락 수정** — 위 조회 경로 수정에서 쓰기
  경로가 빠져 있어, 로그인한 타인이 남의 비공개 글에 좋아요·북마크를 남기거나 댓글·답글을
  달 수 있었고 200/404 응답 차이로 비공개 글의 존재 여부를 알아낼 수 있었다(존재 여부
  오라클). 소유자가 아니면 404로 응답하도록 통일. (`InteractionService.toggleLike`,
  `InteractionService.toggleBookmark`, `CommentService.createComment`,
  `CommentService.createReply`)
- **URL 크롤링 SSRF 차단** — 게시글·댓글 등록 시 서버가 사용자가 입력한 URL로 직접
  요청(크롤링)을 보내는데, 스킴 검사만 있어 내부망·클라우드 메타데이터 엔드포인트
  (예: `169.254.169.254`)로 요청을 보내 응답을 읽어낼 수 있었다. 호스트를 DNS 해석해
  사설/루프백/링크로컬 대역이면 거부하는 `SafeUrlValidator`를 추가하고, 리다이렉트도
  매 홉마다 재검증하도록 변경. (`SafeUrlValidator` 신규, `PostService`, `UrlMetadataExtractor`)
- **JWT access/refresh 토큰이 서로 대체 가능하던 문제 수정** — 두 토큰이 유효기간만
  다르고 페이로드가 동일해, 7일짜리 refresh 토큰을 `Authorization` 헤더로 보내면
  access 토큰처럼 인증되고 반대도 가능했다. 토큰에 타입(`typ`) 클레임을 추가해 용도가
  다르면 거부하도록 수정. **배포 시 기존 발급 토큰이 모두 무효화되어 전 사용자 재로그인이
  필요하다.** (`JwtTokenProvider`, `JwtAuthenticationFilter`, `AuthService`)
- FCM 토큰 삭제(`DELETE /fcm/token`)에 소유권 검증이 없어 로그인한 사용자가 타인의
  토큰 문자열만 알면 그 사람의 푸시를 끊을 수 있던 문제 수정. 같은 기기에서 계정을
  전환하면 토큰이 이전 사용자에게 묶인 채 남아 알림이 잘못 전달되던 문제도 함께 수정.
  (`FcmTokenController`, `FcmTokenService`)
- 죽은 SSE 엔드포인트(`GET /post/ai-events`, 항상 503 고정) 제거 — 쿼리 파라미터로도
  인증을 허용하던 통로였고 실제 사용처가 없어, 토큰이 액세스 로그에 남는 경로만
  남아 있었다. 매 요청마다 토큰 앞부분을 로깅하던 코드도 함께 제거.

### Security

- 운영 JWT 시크릿이 공개 저장소 소스의 기본값(`@Value` 폴백)과 동일하게 설정되어 있던
  것을 확인하고 교체함. 소스의 하드코딩 기본값도 제거해 설정 누락 시 기동이 실패하도록 변경.

## [0.4.0] - 2026-07-28

### Added

- 게시글/북마크 검색에 한/영 자판 미스매칭 보정 폴백 추가 — 검색 결과가 0건일 때만
  2벌식 자판 기준으로 변환한 후보(예: `spdlqj` → `네이버`, `메ㅔㅣㄷ` → `apple`)로
  한 번 더 검색한다. 항상 변환하면 정상 영단어까지 깨질 수 있어 0건일 때만 폴백하며,
  재검색도 0건이면 원문 결과를 그대로 반환한다. 응답(`PostPageResponse`)에
  `correctedSearch` 필드를 추가해 보정 발생 여부를 FE에 전달한다.
  (`HangulKeyboardConverter`, `PostService.getAllPosts`,
  `BookmarkFolderService.getBookmarkedPosts`) — FE 이슈 #8

## [0.3.0] - 2026-07-25

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
- **콜드스타트 첫 요청 53% 단축** — SnapStart 체크포인트 이전(`companion object init`)에
  읽기 전용 엔드포인트로 워밍업 요청을 흘려보내, `DispatcherServlet` 초기화·Security 필터
  체인·Hibernate 쿼리플랜·HikariCP 커넥션 확보를 스냅샷에 포함시킨다. 경로가 실제 매핑과
  다르면(과거 404 사례) 조용히 무력화되므로 응답이 2xx가 아니면 WARN을 남긴다.
  동일 조건(1024MB, 같은 엔드포인트) 비교에서 콜드 첫 요청 2,210ms → 1,046ms,
  총합 2,831ms → 1,729ms. 아래 LWA 레이어 제거가 선행되어야 동작한다.
  측정 방법과 주의사항은 `docs/PERFORMANCE.md` 7장. (`LambdaHandler`)
- Lambda 메모리 1024 → 2048MB — 장애와 무관함이 확인됐고(v41 실험) 비용도 사실상 0이라
  적용했다. 다만 **성능 이득은 아직 확정되지 않았다**(`docs/PERFORMANCE.md` 6장).
- Lambda Web Adapter 레이어 제거 — 이 레이어는 `AWS_LAMBDA_EXEC_WRAPPER`가 설정되지 않아
  익스텐션으로만 떠서 `127.0.0.1:8080`을 폴링했고, 접속에 실패하면 panic하며 **호출 전체를
  502로 실패**시켰다(2026-07-25 장애의 직접 원인). 제거 후 요청은 `LambdaHandler`(MockMvc)가
  처리하며, 응답 본문은 제거 전과 동일하다(헤더명 케이싱만 달라지는데 HTTP 헤더명은 대소문자를
  구분하지 않아 무해). 상세는 `docs/PERFORMANCE.md` 5장.
- 콜드스타트 발생 빈도 감소 — 5분 간격 EventBridge 워밍 핑(`prod` alias 대상)을 추가해
  컨테이너 1개를 살려둔다. 콜드 1회당 소요 시간 자체는 그대로이고, 콜드가 발생하는 비율
  (실측 22%)을 낮추는 변경이다. 측정 기준선은 `docs/PERFORMANCE.md` 참고.
- `spring.jpa.open-in-view`를 `false`로 명시 — 미설정 시 기본값 true라 요청당 커넥션을
  뷰 렌더 시점까지 붙들고 있었고 기동 시마다 경고 로그가 남았다. 함께 `spring.jmx.enabled`를
  `false`로 두어 기동 시 MBean 등록 단계를 생략한다. (`application.yml`)

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

[Unreleased]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.5.0...HEAD
[0.5.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/releases/tag/v0.1.0
