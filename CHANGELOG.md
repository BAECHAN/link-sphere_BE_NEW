# Changelog

이 프로젝트(Link-Sphere BE)의 주요 변경 사항을 기록합니다.

형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따르며,
버전 표기는 [유의적 버전(SemVer)](https://semver.org/lang/ko/)을 사용합니다.

## [Unreleased]

### Added

- **게시글 제목·설명 AI 폴백 추가** — 크롤링이 `og:title`/`<title>`을 못 건져 제목이
  URL 문자열로 남거나(`WeakTitleDetector`로 판정), `og:description`이 없어 설명이
  계속 비어 있던 경우를 AI 분석 잡이 페이지 내용을 보고 대신 채운다. 기존
  `GeminiService.analyzeContent` 프롬프트에 `TITLE`/`DESCRIPTION` 섹션만 추가한
  것이라 Gemini 호출 횟수는 늘지 않았고, 크롤링이 이미 건진 값은 절대 덮지 않는
  순수 폴백이다(URL 수정 시 새 크롤링 값이 없어졌을 때도 동일하게 적용됨). 크롤링
  자체가 실패해 페이지 내용이 없는 경우(AI 잡 자체가 발행되지 않음)는 이번 범위에서
  제외했다. 이미 비동기로 도는 AI 잡 안에서 처리되므로 등록 응답에는 반영되지 않고
  재조회해야 보인다(FE 변경 없음, `docs/AI-ASYNC-PROCESSING.md` 참고).
  (`GeminiService.kt`, `WeakTitleDetector.kt`, `PostAiService.kt`)
- **댓글 이미지 첨부 최대 5장 제한 서버 검증 추가** — FE에 첨부 버튼·드래그앤드롭이 새로
  생기며 1회 첨부 개수에 사실상 상한이 없던 게 두드러지게 됐다. `createComment`/
  `createReply`/`updateComment` 세 곳 모두 `images.size`가 5를 넘으면 400
  (`INVALID_INPUT`)으로 거부한다 — `IllegalArgumentException`을 쓰면
  `GlobalExceptionHandler`가 404로 잘못 매핑하므로 기존 `InvalidInputException`을 재사용했다.
  (`CommentService.kt`)
- **업로드 서명 URL 발급 시 이미지 확장자 allowlist 추가** — 기존엔 영숫자만 걸러내 `exe`·
  `sh`도 통과했다. FE가 실제로 다루는 이미지 포맷(`jpg`, `jpeg`, `png`, `gif`, `webp`,
  `avif`, `heic`, `heif`, `svg`)으로 제한한다. (`UploadService.kt`)
- **서명 URL 미제출 고아 이미지 정리 도구 추가 (로컬 전용, 자동화 아님)** — 댓글 이미지
  5장 확장으로 이 노출이 최대 5배로 늘어난다. 이 코드베이스에 admin/role 개념이 없어 REST
  엔드포인트로 노출하면 로그인한 아무나 전체 버킷을 조회·삭제할 수 있게 되므로,
  `@Profile("cleanup-orphans")`로 가드된 `CommandLineRunner`로 개발자가 필요할 때 로컬에서
  직접 실행하는 형태로 만들었다. Lambda는 `LambdaHandler`가 별도 진입점이라 `main()`을
  거치지 않으므로 배포에는 영향이 없다. 기본은 dry-run(보고만)이고 `--delete` 인자를
  줘야 실제 삭제한다. (`tools/OrphanImageCleanupRunner.kt`,
  `CommentRepository.findAllContent`, `MemberRepository.findAllImageUrls`,
  `SupabaseStorageService.listAllObjectUrls`)

### Fixed

- **URL에 공백이 포함되면 게시글 등록·수정이 항상 400(`INVALID_INPUT`)으로 실패하던 문제** —
  FE `zod .url()`(WHATWG `URL` 파서)은 앞뒤·중간 공백이 섞인 URL도 통과시키지만, BE
  `SafeUrlValidator`가 쓰는 `java.net.URI`는 RFC 2396 엄격 파서라 생 공백에
  `URISyntaxException`을 던진다. FE 검증을 통과한 값이 서버에서 거부되는 계약 불일치였다.
  FE에서 공백을 브라우저 표준과 동일하게 정리(`trim` + 내부 공백 `%20` 인코딩, 한글 등
  비-공백 문자는 원문 보존)해 보내도록 하고, 구버전 클라이언트를 대비해 BE도 저장 전
  앞뒤 공백을 제거한다. (`shared/utils/url.util.ts`, `PostService.createPost`,
  `PostService.updatePost`)
- **400(`INVALID_INPUT`) 응답이 로그를 전혀 남기지 않아 원인 조사가 CloudFront 지표
  역추적에 의존해야 했던 문제** — `handleInvalidInputException`에 `logger.warn` 추가.
  (`GlobalExceptionHandler.kt`)
- **댓글 수정으로 이미지를 제거해도 스토리지 파일이 영구히 남던 문제** — `updateComment`가
  content만 덮어쓸 뿐 빠진 이미지의 스토리지 정리 로직이 없었다. 저장 전후 content에서
  관리 대상 이미지 URL을 비교해 제거된 것만 트랜잭션 커밋 이후(`afterCommit`)에 삭제한다
  — 커밋 전에 지우면 이후 조회 실패로 롤백될 때 DB엔 URL이 남고 파일은 사라진 상태가 될
  수 있다. 본문 텍스트에 직접 써둔 URL은 보존된다. (`CommentService.updateComment`)
- **댓글·게시글 삭제 시에도 스토리지 이미지를 트랜잭션 커밋 이전에 지워 파일만 사라지고
  DB엔 남는 위험이 있던 문제** — `updateComment`는 이미 `afterCommit`으로 처리하고
  있었지만 `deleteComment`(댓글 삭제)와 `deleteImagesForPost`(게시글 삭제 시 딸린 댓글
  이미지 정리)는 DB 작업보다 먼저 스토리지부터 동기로 지우고 있었다 — 뒤이은 좋아요
  삭제·톰스톤/하드 삭제(또는 게시글 삭제)가 실패해 롤백되면 댓글·게시글은 DB에 그대로
  남았는데 파일만 사라진 상태가 될 수 있었다. 두 경로 모두 `updateComment`와 동일한
  `afterCommit` 패턴으로 통일했다. (`CommentService.deleteComment`,
  `CommentService.deleteImagesForPost`)
- **게시글 썸네일(og:image)이 http로 저장되어 HTTPS 페이지에서 Mixed Content 경고가
  뜨던 문제** — 크롤링 대상 사이트가 `og:image`를 http URL로 내리는 경우가 있어, 검증
  없이 그대로 저장하고 있었다. 추출 직후 http를 https로 정규화한다(신규 크롤링 건만
  적용, 기존 게시글은 FE 렌더링 시점에서 별도 처리). (`UrlMetadataExtractor.extract`)

## [0.7.0] - 2026-08-04

### Fixed

- **크롤링 실패 시 URL 전문이 그대로 게시글 제목으로 저장되던 문제** — `UrlMetadataExtractor`의
  실패 폴백이 `title = url`로 URL을 통째로 제목에 넣고 있어, 긴 URL이 그대로 저장되는
  케이스가 있었다. 폴백 제목만 100자로 절삭한다(정상 크롤링된 `og:title`/`<title>`은 원본
  그대로 저장). (`UrlMetadataExtractor.extract`)

### Added

- **닉네임 가용성 사전 조회 엔드포인트 추가** — FE 마이페이지 프로필 수정이 저장 시 서버
  응답(중복 실패 시 409)을 기다리지 않고 즉시 닫히도록 바꾸면서, 중복 여부를 제출 전에
  미리 알려줄 방법이 필요해졌다. `updateAccount`의 기존 중복 검사 조건(자기 자신의 현재
  닉네임은 허용)을 그대로 재사용한다. (`GET /auth/account/nickname-availability`,
  `MemberService.isNicknameAvailable`, `AuthController.checkNicknameAvailability`)

### Changed

- **댓글 등록/수정 요청 경로에서 링크 프리뷰 크롤링과 FCM 알림 발송을 제거, 커밋 후
  별도 Lambda self-invoke로 위임** — 링크 크롤링은 리다이렉트 5홉 × 5초 타임아웃으로 최악
  25초 이상, FCM(`sendEachForMulticast`)도 타임아웃 미설정 블로킹 호출이라 댓글 등록이 이
  둘의 응답 속도에 그대로 묶여 있었다. `PostAIService`와 동일한 패턴(`AFTER_COMMIT` 이벤트 →
  자기 자신 Lambda EVENT 호출)으로 분리해 요청 경로에는 검증+INSERT만 남긴다. 응답의
  `linkMetadata`는 등록 직후엔 `{url, title: url}`만 채워지고 제목·설명·OG 이미지는 다음
  조회 시 채워진다(Slack 방식). (`CommentPostProcessService`, `CommentJobDispatcher`,
  `LambdaSelfInvoker`, `CommentService.createComment/createReply/updateComment`,
  `LambdaHandler.handleCommentJob`)

### Fixed

- **FCM 전송 실패가 댓글 등록 자체를 롤백시키던 버그** — `FcmService.sendToUser`가
  `@Transactional`이라 댓글 저장 트랜잭션에 합류했고, catch 대상이
  `FirebaseMessagingException`뿐이라 전송 계층의 다른 예외가 나면 댓글 INSERT까지 롤백됐다.
  알림을 요청 경로 밖으로 옮기고 catch 범위도 `Exception`으로 넓혔다. (`FcmService.sendToUser`)
- **링크 없이 이미지만 첨부한 댓글이 자기 자신의 이미지 URL을 크롤링하던 버그** — 업로드된
  이미지 URL이 본문 뒤에 이어붙여지는데, 링크 추출 정규식이 이 합쳐진 문자열의 첫 URL을
  그대로 집어 크롤링 대상으로 삼았다. `SupabaseStorageService.isManagedUrl`로 이 버킷
  소속 URL을 걸러내고 첫 "이미지가 아닌" URL만 채택하도록 수정.
  (`CommentService.extractFirstNonImageUrl`)

- **게시글·댓글 삭제 시 첨부 이미지가 스토리지에 고아 파일로 남던 문제** — 댓글은
  `comments.post_id` FK가 `ON DELETE CASCADE`라 게시글 삭제 시 row 자체는 DB에서 함께
  지워지지만, 댓글 본문에 포함된 업로드 이미지는 별도로 정리되지 않았다. 게시글/댓글
  삭제 시 본문에서 이 버킷 소속 이미지 URL을 추출해 Supabase Storage에서 함께 삭제하도록
  변경. (`SupabaseStorageService.deleteObjectsByPublicUrls`, `CommentService.deleteComment`,
  `CommentService.deleteImagesForPost`, `PostService.deletePost`)
- **게시글·댓글 삭제 시 좋아요(reactions)가 영구히 고아로 남던 문제** — `reactions` 테이블이
  `target_id`+`target_type` 폴리모픽 구조라 posts/comments로 FK를 걸 수 없었다. `post_reactions`
  `comment_reactions` 두 테이블로 분리해 `ON DELETE CASCADE` FK를 걸어 DB가 정리를 보장하도록
  변경. 부수적으로 답글이 있어 소프트 삭제(톰스톤)된 댓글도 좋아요가 삭제되고, 톰스톤 댓글에는
  새 좋아요를 누를 수 없도록 막음(`InteractionService.toggleCommentLike`). API 계약(URL·요청·
  응답)은 변경 없음. (`InteractionService`, `PostService`, `CommentService`,
  `PostReactionRepository`, `CommentReactionRepository`)
- **게시글 삭제가 실제로 실패하던 문제** — 위 이미지 정리 수정이 댓글을 `TableComment` 엔티티로
  로드해뒀는데, 같은 트랜잭션에서 그 댓글이 속한 게시글을 `postRepository.delete()`로 지우면
  커밋 시점에 `TransientObjectException`(댓글의 지연 로딩 `post` 연관관계 때문)이 발생해 배포
  직후 실제 삭제 요청이 500으로 실패했다. 댓글 본문만 필요하므로 엔티티 대신 스칼라 프로젝션으로
  조회하도록 변경해 애초에 영속성 컨텍스트에 올라가지 않게 함. 단위 테스트는 실제 Hibernate
  세션을 쓰지 않아 이 문제를 못 잡았음(통합 테스트 부재). (`CommentRepository.findAllContentByPostId`,
  `CommentService.deleteImagesForPost`)

### Migration

- `src/main/resources/sql/create_post_comment_reactions.sql` 실행 완료 — `post_reactions`/
  `comment_reactions` 신설 + `reactions`에서 백필, 컷오버 창 회수 백필까지 완료.
- 소크 기간 확인 후 `src/main/resources/sql/drop_reactions.sql` 실행, 구 `reactions` 테이블
  drop 완료. 파일은 스키마 변경 이력 기록 목적으로 계속 보관(`drop_bookmarks_folder_id.sql`과
  동일 관례).

## [0.6.0] - 2026-08-03

### Added

- **`POST /upload/signed-url` 신설 — 클라이언트가 스토리지에 직접 업로드하도록 서명된
  업로드 URL을 발급** — 이 CloudFront 배포에 붙은 WAF(`AWSManagedRulesCommonRuleSet`의
  `SizeRestrictions_BODY`)가 요청 바디 8KB 초과 시 무조건 차단해, 실제 사진 첨부가
  거의 전부 막혀 있었다(위 멀티파트 버그를 고쳐도 이 문제는 그대로 남음). 이미지 바이트가
  CloudFront/WAF/Lambda를 아예 거치지 않고 Supabase Storage로 직접 전송되도록 바꿔
  이 제한 자체를 무관하게 만든다. (`UploadController`, `UploadService`,
  `SupabaseStorageService.createSignedUploadUrl`)

### Changed

- **댓글 생성/답글/수정 API가 `multipart/form-data`에서 JSON으로 변경됨** — `images`가
  업로드할 파일이 아니라 이미 업로드된 이미지 URL 목록(`List<String>`)이 된다. 클라이언트는
  먼저 `/upload/signed-url`로 URL을 발급받아 Supabase에 직접 업로드한 뒤, 그 결과 URL을
  담아 요청한다. (`CommentController`, `CommentDTO.CreateCommentRequest`, `CommentService`)

### Removed

- **`POST /auth/account/avatar` 엔드포인트 제거** — 위 신규 서명 URL 발급 + 기존
  `PATCH /auth/account`(`image` 필드)로 완전히 대체되어 중복이었다. 같은 이유로
  `SupabaseStorageService.uploadFile`(서버가 대신 업로드하던 구 방식)과
  `AvatarUploadResponse` DTO도 함께 제거. (`AuthController`, `AuthService`, `AuthDTO`)

### Fixed

- **댓글 작성/답글/수정, 아바타 업로드 시 첨부 파일이 있으면 항상 "Content or image must be
  provided" 오류가 발생하던 문제** — Lambda 환경에서 MockMvc로 요청을 처리하는
  `LambdaHandler`가 raw multipart 바이트를 그대로 `.content()`에 넣었는데,
  `MockHttpServletRequest.getParts()`는 raw body를 파싱하지 않고 사전 등록된 Part만
  반환해(spring-test는 테스트 전용 더블이라 파싱 로직 자체가 없음) `@RequestParam`이
  클라이언트가 무엇을 보냈는지와 무관하게 항상 null로 바인딩됐다. Tomcat이 이미 번들하고
  있는 스트리밍 파서(`org.apache.tomcat.util.http.fileupload.FileUpload`)로 raw 바이트를
  직접 해석해 등록하도록 수정했다(`MultipartRequestParser` 신설, 새 의존성 추가 없음).
  임시방편이며 장기 해법은 `aws-serverless-java-container-springboot3`로의 마이그레이션.

## [0.5.1] - 2026-08-02

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
  즉시 실행되게 했다 — 다만 이 예외를 catch 블록에서 삼키고 정상 리턴하면, Hibernate
  세션이 이미 rollback-only로 오염된 상태라 트랜잭션 매니저가 커밋을 시도하다
  `UnexpectedRollbackException`을 새로 던지는 것을 실제 프로덕션 로그로 확인했다(같은
  트랜잭션 안에서는 "삼키고 계속 진행"이 불가능함). 최종적으로 예외를 다시 던져
  트랜잭션을 정상 롤백시키고, 호출자(`LambdaHandler.handleAiJob`)가 그 경계 바깥에서
  "삭제로 인한 정상 레이스"로 INFO 레벨 로깅 후 흡수하도록 수정 — Lambda가 이 호출을
  실패로 보고 재시도하지 않는다. (`PostAIService.processAiJob`, `LambdaHandler.handleAiJob`)
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

[Unreleased]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.7.0...HEAD
[0.7.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.6.0...v0.7.0
[0.6.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.5.1...v0.6.0
[0.5.1]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.5.0...v0.5.1
[0.5.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.4.0...v0.5.0
[0.4.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/BAECHAN/link-sphere_BE_NEW/releases/tag/v0.1.0
