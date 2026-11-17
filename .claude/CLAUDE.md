# Link-Sphere BE — Claude Code Guide

> **행동 원칙은 공통 지침에 있다.** 확인하고 말하기·범위·사용자가 정할 것·전달, 개발 절차(계획·완료의 정의·사람 승인),
> 테스트, 안전·권한, 기록은 `~/project/claude-base/CLAUDE.md`가 정본이고 `~/.claude/CLAUDE.md`가 import한다. 이 파일은
> 이 레포의 사실·명령·컨벤션만 담는다. 공통 지침은 원격이 없는 로컬 저장소라 이 기계 밖 세션에는 로드되지 않는다.
>
> **문서 형식 규칙은 [`.claude/rules/docs-format.md`](rules/docs-format.md)에 있다.** `docs/**/*.md`·`README.md`·
> `CHANGELOG.md`를 읽거나 쓸 때 붙는다. 새 문서를 만들기 전에 같은 폴더의 문서를 하나 읽는다(그래야 첫 초안 전에 규칙이 보인다).

---

## 모르는 것은 세 갈래로 다룬다

조용히 하나를 고르지 않는다. "첫 수정 전에 묻는다" 항목은 작업이 사소해도 묻는다.

- **확인한다**: 코드·문서·설치본 소스로 알 수 있는 것, 선례가 하나인 이름·위치·스타일. 읽고 그대로 따른다.
- **첫 수정 전에 묻는다**: ① 요청이 둘 이상으로 읽히고 해석에 따라 건드리는 파일·동작이 달라질 때(해석을 모두 내놓는다)
  ② 요청에 없는데 사용자가 체감하는 동작이 바뀔 때 ③ 색·간격·문구 톤처럼 정답이 없는 선택 ④ 요청 범위를 넘어
  내 제안을 얹을 때 ⑤ 데이터 계약, 인증·세션, 운영 데이터, 배포 순서 ⑥ 키처럼 보이는 값 ⑦ 선례가 없거나 둘 이상인데
  사용자에게 보이는 결과(화면·문구·API 응답·문서 구조)가 달라지는 선택. 무엇이 헷갈리는지 밝히고, 판단 재료(확인한 사실,
  선택지마다 바뀌는 것, 추천)를 먼저 준 뒤 묻는다.
- **가정을 밝히고 진행한다**: 사용자에게 보이지 않고 그 변경 안에서 되돌릴 수 있는 구현 세부(지역 이름, 내부 함수 분리,
  테스트 케이스 구성)와 승인된 계획 안의 절차뿐이다. 작업한 턴의 판정 줄 위 `**가정**` 줄에 적고, 없으면 "없음"이라 쓴다.
  어느 갈래인지 모르겠으면 묻는다.

## 공통 지침을 이 레포에서 따르는 법

- **라이브러리 소스 확인** — 버전은 `build.gradle.kts`에 적힌 값이 아니라 해석된 값(`./gradlew dependencies`·
  `dependencyInsight`)으로 확인한다. 소스는 Gradle 캐시(`~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/`)의
  `*-sources.jar`를 읽고, 없으면 그 버전 태그의 업스트림 저장소 소스를 **읽기만** 한다(추적 사례: FE `docs/SEARCH.md` §10).

### 계획과 PR 본문

- **계획 파일** — plan mode로 세운 계획은 `docs/plans/<YYYY-MM-DD>-<slug>.md`로 구현 코드와 같은 PR에 커밋한다. plan mode를
  거치지 않은 사소한 즉시 구현에는 적용하지 않는다.
- **대조** — PR을 열기 전에 `plan-verifier`(사용자 범위 에이전트, 정본은 `~/project/claude-base/agents/plan-verifier.md`)에
  계획 파일·diff 파일·대화 요약 파일의 경로를 넘긴다. 이 에이전트는 도구가 Read·Grep·Glob뿐이라 `git diff origin/main...HEAD`를
  스크래치패드 파일로 떨궈 넘기고, 대화 요약은 `node ~/project/claude-base/scripts/session-digest.mjs --out <스크래치패드 파일>`로
  만든다(대화가 그대로 들어 있어 커밋하지 않는다). 쓸 수 없으면 general-purpose에 맡긴다 — 내장 Explore·Plan은 CLAUDE.md를
  로드하지 않는다([sub-agents 문서](https://code.claude.com/docs/en/sub-agents)).
- **PR 본문 `## 계획 대비 구현`** — 대조 결과를 계획 항목별로 "구현됨(파일:줄)/이탈(이유)/미구현"으로 정리하고
  `docs/plans/`의 해당 파일을 링크한다(원문을 PR 본문에 다시 붙이지 않는다). 포맷팅·린트 수정처럼 구현 중 필요했던 세부
  조정은 이탈이 아니다. 계획에 명시된 설계 결정이 다르게 구현된 경우만 이탈이다.
- **구현 결과 블록** — `docs/plans/*.md`는 커밋된 뒤 H1 바로 아래 `구현 결과` 블록 하나만 고친다. 대조자에게 받은 블록 초안을
  같은 PR에서 계획 파일에 붙인다. 형식은 다음과 같다.
  - 줄 맨 앞의 `<!-- plan-result:start … -->`와 `<!-- plan-result:end -->` 사이에 둔다.
  - 그 안에 `## 구현 결과` 제목, `> #PR · 날짜 대조 · 이탈 N · 미구현 N` 한 줄, 이탈(이유 종류)·미구현·계획에
    없던 변경을 "무엇이 어떻게 달라졌나"로 적은 목록을 둔다.
  - `파일:줄` 근거는 PR 본문에만 둔다. 차이가 없으면 "없음" 한 줄로 쓴다.
  - 표식 예시를 계획 본문에 적을 때는 들여쓰거나 인라인 코드로만 쓴다.

  나중에 다른 계획이 이 계획을 뒤집으면 그 PR에서 이 블록에 `대체됨: <새 계획>(#PR)`을
  덧붙인다(블록이 없던 옛 계획이면 새로 만든다). 그래서 계획 파일만 열어도 실제로 무엇을
  했는지 알 수 있다. CI(`ci.yml`의 "docs/plans 불변성 확인" 스텝)는 블록과 빈 줄을 뺀 나머지가
  바뀌면 막는다. 근거와 형식 예시는 `docs/plans/2026-11-03-plan-result-block.md`(FE와 같은 사본).

---

## 프로젝트 공통 컨텍스트

- **BE**: Spring Boot + Kotlin, port 8080, context-path `/api`
- **FE**: React + TypeScript + Vite, FSD 아키텍처, port 31119
- **배포**: CloudFront → `/api/*` Lambda(BE), `/*` S3(FE)
- **Lambda**: Function URL 기반, CloudFront 뒤에 배치. arm64 / 2048MB / SnapStart, **레이어 없음**
- **커밋**: 작업 전 `.gitmessage` 파일 먼저 읽고 형식 준수

---

## Critical Rules

- **Never** `Authentication.name`·`Principal.name` 직접 파싱 → 항상 `authentication.getUserId()` 확장 함수 사용 (`global/common/SecurityUtils.kt`). `Principal`·`@AuthenticationPrincipal` 파라미터도 쓰지 않는다 (`onlySecurityUtilsReadsPrincipalName`·`ControllerSignatureRulesTest`가 막는다)
- **Never** Controller에서 비즈니스 로직 → 반드시 Service 레이어로 위임. 예외는 사용자 요청 경로에만 거는 레이트리밋(`rateLimitService.consume`)이다 — 봇(`FeedItemProcessor`)이 Service를 직접 부르므로 한도는 컨트롤러에서 건다(BE-ARCHITECTURE §3). Repository 직접 참조는 `controllersMustNotDependOnRepositories`, 엔티티 참조는 `controllersMustNotDependOnEntities`가 막는다
- **Never** Repository에서 직접 예외 throw → Service에서 처리 (`repositoriesMustNotConstructExceptions`가 막는다)
- **Never** `IllegalArgumentException` throw → 의미가 뭉개진다(예전에는 핸들러가 404 `NOT_FOUND`로 바꿔 빈 댓글 같은 입력 오류가 "없음"으로 나갔다). 입력 오류는 `InvalidInputException`, 대상 없음은 `*NotFoundException`, 내부 불변조건 위반은 `IllegalStateException`(BE-ARCHITECTURE §4). `appCodeMustNotConstructIllegalArgumentException`가 막는다
- **Never** `GlobalExceptionHandler` 수정 없이 새 예외 클래스 추가 → 핸들러에 `@ExceptionHandler` 반드시 등록 (`ExceptionHandlerCoverageTest`가 막는다)
- **Never** Controller에서 직접 HTTP 상태코드 하드코딩 → `HttpStatus.*` 상수 사용 (`ApiResponse(HttpStatus.OK.value(), …)`, `HttpStatusLiteralTest`가 막는다)
- **Never** Security 인증 없이 사용자 식별 → 인증 필수 엔드포인트는 `authentication: Authentication`(non-null), 비로그인을 허용하는 공개 엔드포인트만 `Authentication?` + `@SecurityRequirements`이고 null이면 비로그인으로 명시적으로 처리 (`ControllerSignatureRulesTest`가 막는다)
- **Never** Lambda Web Adapter 레이어를 다시 붙이지 않는다 → 2026-07-25 502 장애의 직접 원인이었고, `LambdaHandler.warmUp()`은 이 레이어가 **없는 상태를 전제**로 한다. 붙이면 워밍업이 깨진다 (`docs/PERFORMANCE.md` 5장)
- **Never** 검증 없이 `prod` alias 이동 → 정상 배포는 2026-08-13부터 `deploy.yml`이
  자동으로 처리한다(발행 → 새 버전 직접 5회 연속 호출 → 전부 통과해야 승격, 하나라도
  실패하면 워크플로우가 죽고 `prod`는 이전 버전 유지). 이 규칙은 이제 **롤백 등
  CI를 우회하는 예외적 수동 개입에만** 적용된다 — 그럴 때도 반드시 버전을 **직접
  연속 호출**해 확인한 뒤 옮긴다. 위 장애는 "복원 후 첫 요청은 성공, 이후 실패"
  패턴이라 단발 확인으로는 잡히지 않았다. 로컬 키에는 Lambda 호출·쓰기 권한이 없어(2026-11-06 회수) 사용자가 CloudShell에서 실행한다
  ```bash
  aws lambda invoke --function-name link-sphere-api:<버전> --log-type Tail \
    --payload fileb://event.json /tmp/out.json --query 'LogResult' --output text | base64 -d
  ```
- **Never** 인프라·배포·아키텍처 변경 후 문서 갱신 누락 → BE `README.md`·`docs/DEPLOY.md`와 함께 **FE `docs/SYSTEM-ARCHITECTURE.md`** 도 확인한다(BE 인프라를 서술하고 있어 가장 놓치기 쉽다). 기능 변경도 같다 — 반대편 레포의 서사형 문서(`docs/*-BOT.md` 등)가 바뀐 동작을 서술하고 있으면 같은 변경에서 고치고 보고에 따로 적는다(공통 지침 "완료의 정의")
- **Never** `CommentLimits.MAX_CONTENT_BYTES`(현재 6,000)를 CloudFront WAF 상태 확인 없이 올리지 않는다 → CloudFront에 붙은 WAF의 `SizeRestrictions_BODY`가 요청 바디 8,192바이트 초과를 차단한다(AWS 기본값, 코드로 추적 안 됨). 대체 크기 제한 룰은 CloudFront Pro 플랜(월 $15) 전용이라 Free 플랜인 이 계정에서는 만들 수 없다. 이 상수를 올리려면 먼저 WAF 상태를 확인한다(경위·재적용 절차: FE `docs/DEPLOY.md`의 "CloudFront WAF (수동 관리)" 절, FE `docs/DECISIONS.md` 2026-09-06 항목)
- **Never** 운영 DB 임시 조회에 앱 계정·트랜잭션 풀러 6543을 쓰거나 세션 수준 `SET`을 건다 → 항상 읽기 전용 계정으로 `/opt/homebrew/opt/libpq/bin/psql "service=linksphere-ro" -c "…"`. 쓰기는 사용자가 기존 계정으로 직접 한다. `members`·`fcm_tokens`에 컬럼을 추가하는 마이그레이션에는 `grant select (<새 컬럼>) on public.<테이블> to adhoc_readonly;`를 같이 넣는다(계정·사건 경위: `docs/DEPLOY.md` 12장)

### 워크트리

코드 수정은 워크트리에서만 하고 메인 체크아웃에서는 스테이징하지 않는다. 이 규칙은 공통 지침 "안전·권한"과 가드 훅
(`~/project/claude-base/hooks/`의 edit-guard·bash-guard, 레포별 사실은 `.claude/repo-policy.json`)이 맡는다 — 막히면 안내하는
명령으로 바꾸고 우회하지 않는다. 아래는 이 레포의 사실이다.

- **Never** 워크트리 진입 후 부트스트랩 생략 → `EnterWorktree`로 만든 워크트리는 gitignore된
  설정 파일이 없다. 진입 직후 반드시 실행:
  ```bash
  ./scripts/worktree-bootstrap.sh
  ```
  메인 체크아웃의 비밀 설정 파일 2개를 복사한다. `cp`를 직접 쓰지 않는다 — Claude 세션은 이 파일들의 이름이 든
  명령(`cp`·`ls` 포함)을 실행하지 못한다(사용자 설정 deny와 claude-base bash-guard G6). 이 파일들의 키 이름이 필요하면 값을
  읽지 않고 `application.yml`·코드의 `${...}` 참조에서 찾거나 사용자에게 묻는다
- **Never** 여러 워크트리에서 동시에 `bootRun` → 포트(8080)와 DB(원격 Postgres, `ddl-auto: none`)
  는 워크트리로 격리되지 않는다. dev 서버는 한 번에 한 워크트리에서만 띄운다
- **Never** `EnterWorktree` 기본값(`fresh` = `origin/main` 기준)을 확인 없이 사용 → 작업 시작 전 `git log origin/main..main`으로
  미푸시 커밋이 있는지 먼저 확인한다
- **Never** 병합·배포까지 확인된 워크트리를 남겨 두지 않는다 → 병합과 그 병합으로 도는 배포 성공까지 확인했으면(배포가
  트리거되지 않는 변경이면 병합 확인까지) `ExitWorktree(action: "remove")`로 워크트리와 로컬 브랜치를 지운다(원격 브랜치는 레포 설정
  `delete_branch_on_merge`가 병합 때 자동으로 지운다). 배포가 실패하면
  원인을 다 볼 때까지 남기고, 병합 전에 세션을 떠날 때는 `ExitWorktree(action: "keep")`으로 나온다. `ExitWorktree(remove)`가
  거부되면(다른 세션이 만들었거나 `EnterWorktree({ path })`로 재진입한 경우) 손으로 지우되, squash 병합이라 `git branch -d`가 안
  되므로 먼저 ① `gh pr view <번호> --json state,headRefOid`가 `MERGED`이고 ② `headRefOid`가 `git -C <경로> rev-parse HEAD`와 같고
  ③ `git -C <경로> status --porcelain`이 비어 있는지 확인한 뒤 `git worktree remove <경로>`(`--force` 없이) +
  `git branch -D <브랜치>`. 새 워크트리를 만들기 전 `git worktree list`로 목록을 훑고, 디렉토리는 있는데 목록엔 없는
  경우(비정상 종료로 등록이 깨진 경우)만 `git worktree prune`으로 정리한다
- **Never** 새 lint/format 도구를 들이면서 ignore 동작을 확인하지 않는다 → `.claude/worktrees/` 같은 중첩 경로에 빌드 산출물을
  만들어 실제로 걸러지는지 확인한다. ignore 문법은 도구마다 다르다(FE 사례: FE `.claude/CLAUDE.md` "워크트리"의 ESLint
  `**/` 규칙)

---

## 아키텍처와 패턴 — 정본은 `docs/BE-ARCHITECTURE.md`

요청 파이프라인, 패키지 역할, 레이어 규칙과 허용 예외, 응답·에러 계약(code 표), 인증, 트랜잭션, 패턴 카탈로그,
테스트 패턴, 체크리스트는 [`docs/BE-ARCHITECTURE.md`](../docs/BE-ARCHITECTURE.md)가 정본이다 — 여기 복사해두지 않는다.
이 자리에 패키지 트리·예외 코드표·레퍼런스 표를 직접 두었다가 코드가 바뀌며 낡은 것을 2026-10-25 전체 점검에서 발견했다
(패키지 6개 누락, 코드표 20개 이상 누락, 이미 없어진 토글 패턴을 정석으로 안내 — `docs/plans/2026-10-25-be-architecture-audit.md`).
FE가 `docs/FE-ARCHITECTURE.md`를 정본으로 두고 CLAUDE.md에 요약표만 두는 것과 같은 방식이다. 새 엔드포인트·도메인·예외·
배치 잡·외부 클라이언트를 만들기 전에 해당 절을 **먼저 읽는다**. 그 문서 §9 "알려진 예외"에 있는 코드는 본뜨지 않는다.

| 패턴 | 정본 | 한 줄 요약 |
| ---- | ---- | ---------- |
| 정석 컨트롤러 | §5 | `LinkPreviewController` — non-null `Authentication` + `getUserId() ?: throw IllegalStateException`, `ApiResponse(HttpStatus.X.value(), …)` |
| 레이어 골격 | §3 | 엔티티는 Service·Assembler 안에서만 응답 DTO로 바뀐다(컨트롤러는 엔티티를 모른다). 아래 계층은 위를 모른다 — `LayerSkeletonRulesTest` |
| 공용 헬퍼 | §7-6·§7-10·§7-12 | 커밋 뒤 부수효과는 `TransactionCallbacks.afterCommit`, 비공개 글 404는 `PostVisibility.assertVisible`(post 밖에서는 `PostLookupService.requireVisible`), 이메일 인증은 `MemberService.requireEmailVerified`(판정은 member 안의 `EmailVerificationGate`), 배치 마감은 `BatchJobLimits`, 이메일 정규화는 `EmailNormalizer` — 직접 복사하면 검사가 막는 것도 있다 |
| 레이어 규칙·허용 예외 | §3 | 컨트롤러 레이트리밋 허용, 다른 도메인 Repository·엔티티를 직접 쓰는 곳은 0곳이고 새로 쓰지 않음(`ModuleBoundaryRulesTest` — `@Query` 문자열 속 이름도 본다), global → domain은 예외 없이 금지, 컨트롤러가 Service 스칼라를 DTO로 감싸기 허용 |
| 패키지 의존 그래프 | §1·§9 | 실제 그래프는 `PackageGraphDocTest`가 문서와 대조한다. 순환은 없고 허용 간선도 0개다(`packagesMustBeFreeOfCyclesExceptDocumented`가 새 순환을 막는다) |
| 예외 선택·code 표 | §4 | 입력 오류 `InvalidInputException`, 없음 `*NotFoundException`, 내부 불변조건 `IllegalStateException`, IAE 금지 |
| 트랜잭션 | §6 | 새 코드는 클래스 `@Transactional(readOnly = true)` + 쓰기 메서드만 `@Transactional`. rollback-only·`saveAndFlush`·AFTER_COMMIT 예외 삼킴 주의 |
| 멱등 insert + 상태 지정 API | §7-1 | `InteractionService.setPostLike` — 원하는 상태를 받아 `ON CONFLICT DO NOTHING` 또는 일괄 DELETE. 토글·`exists` 후 분기 금지 |
| 조건부 UPDATE 선점 | §7-2 | `MemberSessionRepository.consumeRefreshIfActive` — 영향 행 수(0/1)로 경쟁의 승패를 가른다 |
| upsert·원자적 카운터 | §7-3 | `FcmTokenRepository.upsertToken`, `PostRepository.incrementViewCount` |
| 일괄 DELETE | §7-4 | `deleteBy*`는 `@Modifying @Query`만(`repositoryDeleteMethodsMustBeBulk`) |
| 커밋 뒤 비동기 잡 | §7-5 | AFTER_COMMIT 리스너 → `JobPublisher.publish` → (운영 self-invoke·개발 환경 같은 프로세스) → `JobRunner` → 도메인의 `*JobHandler` |
| 같은 트랜잭션의 동기 이벤트 | §7-5 | `AccountDeletionService`가 `MemberPurgedEvent`를 내고 데이터를 가진 패키지의 `*MemberEventListener`(`@EventListener` + `MANDATORY`)가 지운다. 발행 쪽은 예외를 잡지 않는다 |
| 배치 잡 | §7-7 | `AccountPurgeService` — 90초 마감 + 항목별 `runCatching` + 요약 로그 |
| N+1 없는 조립 | §7-8 | `PostResponseAssembler` — id를 모아 `…In(ids)` 조회 → Map → 조립 |
| 외부 클라이언트 | §7-11 | `YoutubeVideoClient` — RestClient + 명시 타임아웃 + 실패 시 null. `RestTemplate()` 기본 생성 금지 |
| 엔티티 PK | §7-13 | 새 엔티티는 `val id: UUID = UUID.randomUUID()`, `@GeneratedValue`와 섞지 않는다 |
| 테스트 | §8 | Mockito 단위, `@WebMvcTest` + `@WithMockUser(username = <UUID>)`, 쿼리·트랜잭션 버그는 `@PostgresIntegrationTest` |

---

## Security — 새 엔드포인트 공개 허용

기본적으로 모든 요청은 인증이 필요하다. 공개 경로는 `global/config/SecurityConfig.kt`에 **하나씩 나열한다** —
`/auth/**` 같은 와일드카드는 `/auth/logout-all`·`/auth/account`처럼 인증이 꼭 필요한 경로까지 함께 연다. 로그인 여부에
따라 응답이 달라지는 공개 조회(`GET /post` 계열)는 `permitAll()`이 아니라 `.access { … getAttribute("exception") == null }`을
쓴다 — `permitAll()`이면 만료 토큰이 조용히 비로그인으로 처리된다(2026-10-20,
`docs/plans/2026-10-20-public-get-expired-token-401.md`). 실제 목록은 `SecurityConfig.kt`가 정본이고, 컨트롤러 쪽 짝
(`Authentication?` + `@SecurityRequirements`)은 BE-ARCHITECTURE §5.

---

## 개발 커맨드

```bash
./gradlew bootRun          # 로컬 실행 (port 8080)
./gradlew build            # 빌드
./gradlew test             # 단위·아키텍처 테스트 (integration 태그 제외)
./gradlew integrationTest  # 실제 Postgres 통합 테스트 (Docker 필요, PR CI·배포에서 실행)
./gradlew openApiSpec      # 현재 코드의 OpenAPI 스펙을 build/openapi/openapi.json으로 (Docker 필요, PR CI api-compat가 운영과 비교)
./gradlew swaggerUiSite    # 공개 API 문서용 Swagger UI 정적 파일을 build/api-docs-site/로 (스펙은 api-docs-pages.yml이 넣음)
./gradlew verifyShadowJar  # Lambda JAR의 중복 항목·병합 파일·Multi-Release 확인 (shadowJar 뒤에 자동으로 돎)
./gradlew ktlintCheck      # 코드 스타일 검사
./gradlew ktlintFormat     # 코드 스타일 자동 수정
```

---

## 슬래시 커맨드 (`.claude/commands/`)

| 커맨드        | 사용법                                      | 역할                                                                   |
| ------------- | ------------------------------------------- | ---------------------------------------------------------------------- |
| `/new-domain` | `/new-domain bookmark-folder`               | Entity + Repository + DTO + Service + Controller + Exception 일괄 생성 |

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
  스코프는 `post` `comment` `auth` `member` `bookmark` `category` `upload` `infra` `fcm` `config` 중 하나.
- 상세 블록: `<summary>`는 `배경·구현`으로 통일. `<summary>` 다음과 `</details>` 앞에 빈 줄을
  반드시 넣는다(없으면 GitHub이 안의 마크다운을 파싱하지 않는다). 배경·트레이드오프·영향
  파일 목록을 요약 없이 그대로 적는다 — 짧은 항목은 상세 블록을 생략해도 된다.
- `### Migration`·`### Notes`는 접지 않는다 — 배포 시 반드시 봐야 하는 정보다.

**릴리즈 시점** (버전 확정)
1. `[Unreleased]` 항목들을 새 버전 섹션 `## [X.Y.Z] - YYYY-MM-DD` 으로 승격 (빈 `[Unreleased]` 유지), 하단 compare 링크 갱신 (`https://github.com/BAECHAN/link-sphere_BE_NEW`)
2. API 계약(요청/응답 스펙, 필드 추가·제거, permitAll 등)이 바뀌었다면 FE 레포 정본
   [`docs/VERSION-COMPATIBILITY.md`](https://github.com/BAECHAN/link-sphere_FE_NEW/blob/main/docs/VERSION-COMPATIBILITY.md)에도
   상대 레포 최소 버전 행 추가 — BE 자체 사본은 2026-09-14부로 폐지하고 안내
   문서로만 남겼다(경위: FE `docs/DECISIONS.md` 2026-09-14 항목)
3. `chore(release): vX.Y.Z` 커밋을 워크트리 브랜치에 올려 **PR로 머지**한다(#129 방식). main 규칙셋이 필수 체크 `check`·`api-compat`를 요구하고 관리자 예외가 없어 `git push origin main`은 거절된다(`GH013`, 2026-10-14부터 — `docs/CI-CHECK-GATE.md` §5 "main 브랜치 규칙셋"). 머지되면 아래 4번의 `release.yml`이 main push를 감지한다
4. **태그·GitHub Release는 수동으로 만들지 않는다** — `.github/workflows/release.yml`이 `CHANGELOG.md` push를 감지해 최신 버전 섹션을 파싱, 동명 태그가 없으면 자동으로 태그 생성 + `gh release create`까지 수행한다(이미 있으면 스킵하는 멱등 동작). `git tag`/`gh release create`를 직접 실행할 필요 없음.
- 버전 체계: 정식 릴리즈 전 개발 단계 = `0.x`. 현재 버전은 `CHANGELOG.md`의 최신 `## [X.Y.Z]` 섹션(또는 하단 compare 링크)에서 확인한다 — 릴리즈마다 바뀌는 값이라 여기 숫자로 적지 않는다(`0.10.0`으로 적혀 있다가 v0.11.0 릴리즈 뒤 낡았다, 2026-10-28. FE `changelog-release` skill과 같은 방식)

## 문서 형식

문서 위치, `docs/` 안 분류, 서사형 작업 문서의 형식은 [`.claude/rules/docs-format.md`](rules/docs-format.md)에 있다. 문서를
읽거나 쓸 때 자동으로 붙고, 새 문서를 만들기 전에는 같은 폴더의 문서를 하나 읽어 규칙이 먼저 보이게 한다.
