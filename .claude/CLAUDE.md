# CLAUDE.md

흔한 LLM 코딩 실수를 줄이기 위한 행동 지침이다. 프로젝트별 지침과 병합해서 사용한다.

**트레이드오프:** 이 지침은 속도보다 신중함을 우선한다. 사소한 작업에는 판단력을 발휘한다.

> 원문 출처: [multica-ai/andrej-karpathy-skills](https://github.com/multica-ai/andrej-karpathy-skills)의
> `CLAUDE.md`. §1~7은 그 문서를 옮기고 이 레포 사정에 맞게 확장한 것이다.

## 1. 코딩 전에 먼저 생각한다

**추측하지 않는다. 혼란을 숨기지 않는다. 트레이드오프를 드러낸다.**

구현하기 전에:

- 가정을 명시적으로 밝힌다. 확신이 없으면 묻는다.
- 여러 해석이 가능하면 모두 제시한다 — 조용히 하나를 고르지 않는다.
- 더 단순한 접근이 있으면 그렇게 말한다. 타당하면 반대 의견을 낸다.
- 무언가 불분명하면 멈춘다. 무엇이 헷갈리는지 명시한다. 묻는다.
- 기존 시스템이 지금 어떻게 동작하는지 주장할 때("우리는 X 패턴을 따른다", "이건 Y
  프레임워크를 그대로 따른 것이다") 먼저 실제 코드 경로를 추적한다 — 비슷하게 생긴
  유틸리티나 외부 프레임워크의 동작 방식으로부터 아키텍처를 추측하지 않는다. 그 주장이
  추적한 사실인지 추측인지 명확히 밝힌다.
- 원인 진단이나 설계 판단이 외부 라이브러리·프레임워크의 내부 동작(Spring·Hibernate·
  HikariCP·AWS SDK가 언제 무엇을 하는지 등)에 기대면, "추론"이라고 표시만 하고 넘어가지
  않는다 — 실제로 쓰는 버전의 소스를 직접 따라가 확인한 뒤에 말하고, 버전과 파일·줄을 함께
  남긴다. 버전은 `build.gradle.kts`에 적힌 값이 아니라 해석된 값(`./gradlew dependencies`·
  `dependencyInsight`)으로 확인한다. 소스는 Gradle 캐시
  (`~/.gradle/caches/modules-2/files-2.1/<group>/<artifact>/<version>/`)의 `*-sources.jar`를
  읽고, 없으면 그 버전 태그의 업스트림 저장소 소스를 **읽기만** 한다. 공식 문서·블로그·기억은
  다른 버전 기준일 수 있어 대신하지 못한다. 끝내 따라갈 수 없으면 확인하지 못했다고 밝히고,
  그 가정에 기댄 결론을 확정처럼 쓰지 않는다(2026-10-16 FE 세션에서 "`useQuery`는 커밋 뒤에야
  요청한다"를 추론으로 둔 채 해결 범위를 물었다가 사용자 지적으로 설치본 소스를 따라가 확인한
  사례 — 문서와 설치본이 실제로 달랐다. FE `.claude/CLAUDE.md` §1의 같은 규칙, FE #403).

## 2. 단순함이 우선이다

**문제를 해결하는 최소한의 코드. 추측성 코드는 없다.**

- 요청받지 않은 기능은 넣지 않는다.
- 한 번만 쓰이는 코드에 추상화를 두지 않는다.
- 요청받지 않은 "유연성"이나 "설정 가능성"을 넣지 않는다.
- 일어날 수 없는 상황에 대한 에러 처리를 하지 않는다.
- 200줄을 썼는데 50줄로 줄일 수 있다면 다시 쓴다.

스스로에게 묻는다: "시니어 엔지니어가 보면 과하게 복잡하다고 할까?" 그렇다면 단순화한다.

## 3. 최소 범위만 수정한다

**꼭 필요한 것만 건드린다. 자신이 만든 것만 정리한다.**

기존 코드를 수정할 때:

- 인접한 코드·주석·포맷을 "개선"하지 않는다.
- 고장 나지 않은 것을 리팩터링하지 않는다.
- 자신의 취향과 다르더라도 기존 스타일을 그대로 따른다.
- 무관한 죽은 코드를 발견하면 언급만 하고 지우지 않는다.

내 변경으로 고아가 생기면:

- 내 변경으로 인해 쓰이지 않게 된 import/변수/함수는 제거한다.
- 원래 있던 죽은 코드는 요청이 없으면 지우지 않는다.

기준: 변경한 모든 줄이 사용자의 요청으로 바로 설명돼야 한다.

## 4. 목표 기반으로 실행한다

**성공 기준을 정의한다. 검증될 때까지 반복한다.**

작업을 검증 가능한 목표로 바꾼다:

- "검증 추가" → "잘못된 입력에 대한 테스트를 작성하고, 통과시킨다"
- "버그 수정" → "버그를 재현하는 테스트를 작성하고, 통과시킨다"
- "X 리팩터링" → "리팩터링 전후로 테스트가 통과하는지 확인한다"

여러 단계로 이뤄진 작업이면 짧은 계획을 먼저 밝힌다:

```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

강한 성공 기준이 있으면 혼자서도 반복 작업이 가능하다. 약한 기준("일단 되게 만들기")은 계속 확인을 요구하게 만든다.

## 5. 수정 전에 영향 범위를 점검한다

**코드를 쓰기 전에 무엇이 깨질 수 있는지 나열한다. 새로 만드는 것과 이미 동작하는 것, 양쪽 다.**

구현하기 전에 다음을 점검하고 보고한다:

- **CRUD 실패 지점** — 이 변경이 건드리는 데이터에 대해 등록(create) / 수정(update) /
  읽기(read) / 삭제(delete)를 하나씩 짚는다. 각 단계에서 무엇이 깨지는지 명시한다:
  누락된 행, 중복/멱등성, 소유권·가시성 체크, cascade 동작, count/페이지네이션 정확성,
  동시 요청.
- **기존 기능의 회귀** — 깨질 수 있는 모든 기존 동작을 그 동작을 소유한 파일과 함께
  나열한다. 공유 쿼리와 캐시/무효화 경로, 파생 카운트, 이전 계약을 담고 있는 기존
  테스트, 이전 동작을 단정하는 문서·사용자 노출 문구, 여전히 컴파일되는 죽은/미사용
  코드 경로를 포함한다.

두 목록 모두 첫 수정 전에 보고한다, 수정 후가 아니라. 변경이 데이터 계약(스키마, DTO,
API 형태)을 바꾼다면 배포 순서와 그 사이에 무엇이 깨지는지 명시적으로 밝힌다.

## 6. 새로 만들기 전에 선례를 찾는다

**이 코드베이스가 이미 어떻게 풀었는지 찾는다. 그 형태를 그대로 따른다.**

새로운 것을 설계하기 전에:

- 같은 부류의 문제를 푼 기존 기능을 찾아 읽는다.
- 코드를 쓰기 전에 그 선례를 파일 경로로 명시한다.
- 그 형태를 따른다: 레이어링, 네이밍, 캐시/롤백 전략, 에러 소유권.
- 명시한 이유가 있을 때만 벗어난다 — 그리고 그 이유를 밝힌다.

이건 기존 함수를 확장하는 게 아니라 이미 자리 잡은 *형태*를 재사용하는 것이다.
선례를 따르는 새 hook/util을 작성하는 것이 기대되는 결과다.

기준: "어떤 기존 파일을 본떠 만들었는가?"에 항상 답할 수 있어야 한다.

## 7. 사용자가 체감하는 트레이드오프는 승인을 받는다

**기술적 제약의 부수 효과도 UX 결정일 수 있다. 조용히 떠안지 않는다.**

어떤 결정은 순전히 기술적으로 보이지만("Y 때문에 X를 히스토리에 묶을 수 없다")
사용자가 실제로 체감하는 결과를 낳는다("그래서 뒤로가기를 누르면 모달만 닫히는 게
아니라 페이지가 이동한다"). 그 결과는 논리적으로 제약에서 나왔더라도 기술적으로
불가피한 게 아니라 UX 결정이다. 확정된 것으로 취급하기 전에 드러내고 확인받는다.

- 근거가 뒷받침하는 것보다 리서치/선례를 더 강하게 인용하지 않는다. 어떤 출처가
  관련은 있지만 다른 상황을 다룬다면 그렇게 그대로 말한다("X 출처는 Y에 관한 것이지
  정확히 이 케이스는 아니다") — 지금 결정을 뒷받침하는 것처럼 암시하지 않는다.
- 과거 결정에 이의가 제기되면, 방어하기 전에 근거를 다시 검증한다. 더 확신에 찬
  말투로 되풀이하기보다 원래 주장이 실제로 성립하는지 확인한다.
- 사용자에게 묻고 동의를 받은 순간을 짚을 수 없다면, 대신 결정해버린 것이다 —
  뒤늦게라도 그 사실을 알리고 확인받는다.

기준: 내가 전달한 내용만 보고도 사용자가 "이건 내가 관여하지 못한 판단이었다"는 걸
알아챌 수 있는가? 그렇지 않다면 사용자 대신 결정한 것이다.

## 8. 계획을 세웠으면 배포 전에 계획과 실제 구현을 대조한다

**"계획대로 됐다"는 스스로 판단하지 않는다. fresh subagent에게 diff를 계획과 대조시켜
증거로 보여준다.**

Plan mode로 계획을 세우고 구현한 작업은, PR을 열기 전에:

- 계획 파일을 `docs/plans/<YYYY-MM-DD>-<slug>.md`로 구현 코드와 같은 PR에 커밋한다.
  plan mode를 거치지 않은 사소한 즉시 구현에는 적용하지 않는다(계획을 세울 만큼
  중요한 작업만 이 절차의 대상이다).
- 구현한 세션 스스로 "계획대로 됐다"고 결론 내리지 않는다 — 방금 쓴 코드에 편향되기
  쉽다. 대신 fresh subagent(Explore 타입)에게 커밋한 계획 파일과 실제 diff를 함께
  주고 대조를 맡긴다: 계획의 각 항목이 실제로 구현됐는가, **계획에 없던 변경(과잉
  구현 포함)이 섞였는가**, 계획과 다르게 구현된 부분이 있다면 왜인가.
- 대조 결과를 계획 항목별로 "구현됨(파일:줄)/이탈(이유)/미구현" 형태로 정리해 PR
  본문에 `## 계획 대비 구현` 섹션으로 남기고, `docs/plans/`의 해당 파일을 링크한다
  (원문을 PR 본문에 다시 붙여넣지 않는다 — 커밋된 파일과 중복되면 SSOT가 깨진다).
  CI green만 보고 머지하는 게 아니라, 계획이 실제로 지켜졌는지 그 자리에서 확인할
  수 있어야 한다.
- 이 대조는 "올바른 모양인가"(→ 아키텍처·스타일 리뷰가 이미 다룬다)가 아니라 "약속한
  것을 만들었는가"만 본다. 포맷팅·린트 수정처럼 구현 중 자연스럽게 필요했던 세부
  조정은 이탈로 꼽지 않는다 — 계획에 명시된 설계 결정(트레이드오프 선택, 트리거 조건
  등)이 실제로 다르게 구현된 경우만 이탈로 표시한다.
- `docs/plans/*.md`는 커밋된 뒤 수정하지 않는다(append-only, ADR·DB 마이그레이션
  파일과 같은 컨벤션). CI가 기존 파일의 수정을 막는다(`ci.yml`의 "docs/plans 불변성
  확인" 스텝) — 이탈은 새 파일이 아니라 PR 본문에 적는다.

기준: PR을 읽는 사람이 코드를 한 줄도 안 읽고 이 섹션만 봐도 계획과 실제가 일치하는지
판단할 수 있는가?

## 9. 여러 단계를 거치는 흐름은 다이어그램으로도 보여준다

**텍스트만으로 설명하지 않는다. 단계·분기·실패 지점이 여러 개면 그림을 곁들인다.**

여러 단계를 거치는 흐름(요청→처리→저장), 실패 지점이 여러 곳인 진단, 분기가 있는 설계
대안, 작업 순서를 설명할 때는 산문만으로 끝내지 않는다.

- 파일(계획 파일 `docs/plans/*.md`, 독립 기능 문서, PR 본문)에는 **Mermaid**를 쓴다 —
  GitHub과 대부분의 에디터가 도형으로 렌더한다.
- 터미널 대화 답변에는 **ASCII 다이어그램**을 쓴다 — 터미널은 Mermaid 코드 펜스를 그림이
  아니라 텍스트 그대로 보여주므로 목적을 이루지 못한다.
- 단일 파일 한 줄 수정처럼 흐름이랄 게 없는 작업에는 적용하지 않는다.

아래 "서사형 작업 문서 형식" 절의 "장 끝에 프로세스 Mermaid 순서도를 반드시 넣는다"는 독립 기능 문서로만 한정돼
있다 — 이 절은 그 범위를 계획 파일·PR 본문·대화 답변까지 넓힌 것이다(2026-09-04 세션에서
"RSS-FEED-BOT.md를 순서도로 보여주면 더 이해하기 쉬울것"이라는 요청이 있었는데 그때는
독립 기능 문서 규칙으로만 반영돼, 2026-09-09 다른 작업의 계획 설명 때 같은 지적을 다시
받았다).

기준: 이 설명을 읽는 사람이 글을 한 줄도 안 읽고 그림만 봐도 전체 흐름을 따라올 수 있는가?

---

**이 지침들이 잘 작동하고 있다는 신호:** diff에 불필요한 변경이 줄어들고, 과하게
복잡하게 만들어 다시 쓰는 일이 줄어들고, 구현 후가 아니라 구현 전에 확인 질문이
나온다.

# Link-Sphere BE — Claude Code Guide

---

## 프로젝트 공통 컨텍스트

- **BE**: Spring Boot + Kotlin, port 8080, context-path `/api`
- **FE**: React + TypeScript + Vite, FSD 아키텍처, port 31119
- **배포**: CloudFront → `/api/*` Lambda(BE), `/*` S3(FE)
- **Lambda**: Function URL 기반, CloudFront 뒤에 배치. arm64 / 2048MB / SnapStart, **레이어 없음**
- **커밋**: 작업 전 `.gitmessage` 파일 먼저 읽고 형식 준수
- **커밋 단위**: 대화 턴(요청)마다 나누지 않고, 논리적으로 완결된 기능·수정 단위로 나눈다.
  같은 기능을 다듬는 과정에서 나온 후속 수정(버그 픽스 포함)은 원래 커밋에 합치고,
  서로 무관한 변경끼리만 별도 커밋으로 분리한다.

---

## Critical Rules

- **Never** `Authentication.name`·`Principal.name` 직접 파싱 → 항상 `authentication.getUserId()` 확장 함수 사용 (`global/common/SecurityUtils.kt`). 아직 어기는 곳은 `docs/BE-ARCHITECTURE.md` §9에 있다 — 본뜨지 않는다
- **Never** Controller에서 비즈니스 로직 → 반드시 Service 레이어로 위임. 예외는 사용자 요청 경로에만 거는 레이트리밋(`rateLimitService.consume`)이다 — 봇(`FeedItemProcessor`)이 Service를 직접 부르므로 한도는 컨트롤러에서 건다(BE-ARCHITECTURE §3). Repository 직접 참조는 `controllersMustNotDependOnRepositories`가 막는다
- **Never** Repository에서 직접 예외 throw → Service에서 처리 (`repositoriesMustNotConstructExceptions`가 막는다)
- **Never** `IllegalArgumentException` throw → 전역 핸들러가 404 `NOT_FOUND`로 바꿔 입력 오류가 "없음"으로 보인다. 입력 오류는 `InvalidInputException`, 대상 없음은 `*NotFoundException`, 내부 불변조건 위반은 `IllegalStateException`(BE-ARCHITECTURE §4). 남은 곳은 §9
- **Never** `GlobalExceptionHandler` 수정 없이 새 예외 클래스 추가 → 핸들러에 `@ExceptionHandler` 반드시 등록 (`ExceptionHandlerCoverageTest`가 막는다)
- **Never** Controller에서 직접 HTTP 상태코드 하드코딩 → `HttpStatus.*` 상수 사용 (`ApiResponse(HttpStatus.OK.value(), …)`, `HttpStatusLiteralTest`가 막는다)
- **Never** Security 인증 없이 사용자 식별 → `Authentication?`이 null이면 인증 안 된 것, 명시적으로 처리
- **Never** 대상 파일 양식 무시하고 코드 생성 → 항상 붙여넣을 파일(및 인접 코드)을 **먼저 읽고** 들여쓰기·네이밍·import 순서·따옴표·주석 밀도·정렬을 그대로 맞춘다. 본인 스타일을 강요하거나 기존 코드를 재포맷하지 않는다
- **Never** Lambda Web Adapter 레이어를 다시 붙이지 않는다 → 2026-07-25 502 장애의 직접 원인이었고, `LambdaHandler.warmUp()`은 이 레이어가 **없는 상태를 전제**로 한다. 붙이면 워밍업이 깨진다 (`docs/PERFORMANCE.md` 5장)
- **Never** 검증 없이 `prod` alias 이동 → 정상 배포는 2026-08-13부터 `deploy.yml`이
  자동으로 처리한다(발행 → 새 버전 직접 5회 연속 호출 → 전부 통과해야 승격, 하나라도
  실패하면 워크플로우가 죽고 `prod`는 이전 버전 유지). 이 규칙은 이제 **롤백 등
  CI를 우회하는 예외적 수동 개입에만** 적용된다 — 그럴 때도 반드시 버전을 **직접
  연속 호출**해 확인한 뒤 옮긴다. 위 장애는 "복원 후 첫 요청은 성공, 이후 실패"
  패턴이라 단발 확인으로는 잡히지 않았다
  ```bash
  aws lambda invoke --function-name link-sphere-api:<버전> --log-type Tail \
    --payload fileb://event.json /tmp/out.json --query 'LogResult' --output text | base64 -d
  ```
- **Never** 인프라·배포·아키텍처 변경 후 문서 갱신 누락 → BE `README.md`·`docs/DEPLOY.md`와 함께 **FE `docs/SYSTEM-ARCHITECTURE.md`** 도 확인한다 (BE 인프라를 서술하고 있어 가장 놓치기 쉽다). 이 규칙은 인프라급이 아닌 **기능 변경**에도 그대로 적용한다 — 반대편 레포의 서사형 문서(`docs/*-BOT.md` 등)가 이번에 바뀐 동작을 서술하고 있으면 같은 턴에 찾아 고친다(2026-09-06, FE 봇 글 숨기기 토글의 저장 방식을 URL→localStorage로 바꿨을 때 BE `docs/RSS-FEED-BOT.md` §3의 Playwright 검증 서술이 어긋난 사례). 문서를 고쳤으면 **최종 보고에 "문서 X를 Y로 갱신함"을 별도 항목으로 명시**한다 — 조용히 고쳐두기만 하면 사용자 입장에서는 확인이 안 된 것과 같다
- **Never** `CommentService.MAX_COMMENT_CONTENT_BYTES`(현재 6,000)를 CloudFront WAF 상태 확인 없이 올리지 않는다 → CloudFront에 붙은 WAF의 `SizeRestrictions_BODY`가 요청 바디 8,192바이트 초과를 차단한다(AWS 기본값, 코드로 추적 안 됨). 2026-09-06 이 값을 완화하려 했으나 대체 크기 제한 룰이 **CloudFront Pro 플랜(월 $15) 전용**이라 Free 플랜인 이 계정에서 못 만들어 원복했다 — 지금도 8KB 벽이 살아있다. 이 상수를 올리려면 먼저 WAF 상태를 확인할 것(자세한 내용·재적용 절차는 FE `docs/DEPLOY.md`의 "CloudFront WAF (수동 관리)" 절, FE `docs/DECISIONS.md` 2026-09-06 항목 참고)
- **Never** 운영 DB 임시 조회에 앱 계정(`application-secret.yml`)·트랜잭션 풀러 6543을 쓰거나 세션 수준 `SET`을 건다 → 항상 읽기 전용 계정으로 `/opt/homebrew/opt/libpq/bin/psql "service=linksphere-ro" -c "…"`. 6543은 서버 커넥션을 앱과 돌려 써서 세션 설정이 앱 요청에 남는다 — 2026-10-05 조회 스크립트의 `SET default_transaction_read_only = on`이 1분간 Lambda 쓰기 6건을 500으로 만들었다. 쓰기는 지금처럼 사용자가 기존 계정으로 직접 한다. `members`·`fcm_tokens`에 컬럼을 추가하는 마이그레이션에는 `grant select (<새 컬럼>) on public.<테이블> to adhoc_readonly;`를 같이 넣는다(`docs/DEPLOY.md` 12장)
- **Never** 워크트리 없이 코드 수정 → 이 레포는 여러 Claude 세션이 동시에 돈다. 코드를 **수정하는**
  작업(읽기 전용 조사·질문 답변은 예외)을 시작할 때는 항상 `EnterWorktree`로 워크트리를 만들고
  그 안에서 작업한다. 워킹트리 파일과 `.git/index`(스테이징 영역)를 세션끼리 공유하면 서로
  덮어쓰거나 무관한 커밋에 남의 변경이 딸려 들어간다 — 실제로 겪은 사고 경위는 이 규칙을
  도입한 커밋(`2648c7f`) 메시지 참고. 메인 체크아웃 파일 편집은 FE 레포
  `.claude/hooks/edit-guard.mjs`(사용자 설정에 등록하는 PreToolUse 훅)가 막는다 — gitignore된
  로컬 파일만 예외
- **Never** 메인 체크아웃에서 `git add`/`git rm`으로 스테이징 → 메인 체크아웃의 `.git/index`는
  세션끼리 공유돼 위와 같은 인덱스 오염이 재발한다. 커밋은 `git commit -m "<메시지>" -- <경로...>`로
  대상 파일을 직접 지정하고, 옵션은 반드시 `--` **앞**에 둔다(`git commit -- a.kt -m x`는 `-m`을
  경로로 읽어 실패한다). **워크트리 안에서는** index가 워크트리마다 따로라서
  ([git-worktree 문서](https://git-scm.com/docs/git-worktree), 2026-09-27 PR #29에서
  `git rev-parse --git-dir`로 직접 확인) 다음 세 가지만 허용한다:
  ① 한 번도 추적된 적 없는 새 파일은 `git add -- <새 파일>` 후 커밋 —
  [git-commit 문서](https://git-scm.com/docs/git-commit)상 경로 지정 커밋은 Git이 이미 아는 파일만
  대상으로 한다 ② rebase·merge 충돌 해결 중 해당 파일 add ③ 추적 파일 삭제는 처음부터
  `git rm <경로>`(`rm`이 전역 차단이라 합법 경로는 이것뿐 — `rm`이 거부된 미추적 파일을 지우려는
  우회로는 쓰지 않는다. 그때는 절대경로 `rm` 명령을 사용자에게 제시한다). `git add -A`·`git add .`·`-u`·`--all`·`git commit -a`는 어디서나
  금지(2026-09-29 사용자 결정, 근거와 수치는 FE `docs/plans/2026-09-29-rule-enforcement-hardening.md`).
  이 규칙과 stash·`reset --hard`·`rm` 처리는 FE 레포 `.claude/hooks/bash-guard.mjs`(사용자 설정에
  등록하는 PreToolUse 훅)가 실행 직전에 강제한다 — 막히면 메시지가 안내하는 명령으로 바꾸고,
  우회하지 않는다
- **Never** 워크트리 진입 후 부트스트랩 생략 → `EnterWorktree`로 만든 워크트리는 gitignore된
  설정 파일이 없다. 진입 직후 반드시 실행:
  ```bash
  cp ../../../src/main/resources/application-secret.yml src/main/resources/
  cp ../../../src/main/resources/firebase-service-account.json src/main/resources/
  ```
- **Never** 여러 워크트리에서 동시에 `bootRun` → 포트(8080)와 DB(원격 Postgres, `ddl-auto: none`)
  는 워크트리로 격리되지 않는다. dev 서버는 한 번에 한 워크트리에서만 띄운다
- **Never** `EnterWorktree` 기본값(`fresh` = `origin/main` 기준)을 확인 없이 사용 → 다른 세션이
  로컬 main에만 커밋하고 아직 push하지 않았다면 그 커밋이 빠진 채로 새 워크트리가 갈라진다.
  작업 시작 전 `git log origin/main..main`으로 미푸시 커밋이 있는지 먼저 확인한다
- **Never** 병합·배포까지 확인된 워크트리를 남겨 두지 않는다 → PR을 병합하고 그 병합으로 도는
  배포가 성공한 것까지 확인했으면(배포가 트리거되지 않는 변경이면 병합 확인까지) 그 세션이 바로
  워크트리와 로컬 브랜치를 지운다 — `ExitWorktree(action: "remove")`. 원격 브랜치는 레포 설정
  `delete_branch_on_merge`가 병합 때 자동으로 지운다. 배포가 실패하면 원인을 다 볼 때까지 남겨 두고,
  병합 전에 세션을 떠날 때는 `ExitWorktree(action: "keep")`으로 나온다. 병합되지 않은 워크트리는
  사용자 요청 없이 지우지 않는다(2026-10-14 사용자 결정 — 2026-09-14 "당분간은 냅두자"를 대체).
  `ExitWorktree(remove)`가 거부되면(다른 세션이 만들었거나 `EnterWorktree({ path })`로 재진입한 경우)
  손으로 지우되, squash 병합이라 로컬 브랜치가 main에 병합된 것으로 안 잡혀 `git branch -d`가 안 되므로
  먼저 ① `gh pr view <번호> --json state,headRefOid`가 `MERGED`이고 ② `headRefOid`가
  `git -C <경로> rev-parse HEAD`와 같고 ③ `git -C <경로> status --porcelain`이 비어 있는지 확인한 뒤
  `git worktree remove <경로>`(`--force` 없이) + `git branch -D <브랜치>`. 새 워크트리를 만들기 전
  `git worktree list`로 목록을 훑고, 디렉토리는 있는데 목록엔 없는 경우(비정상 종료로 등록이 깨진
  경우)만 `git worktree prune`으로 정리한다
- **Never** 새 lint/format 도구의 ignore 패턴을 루트 상대 경로로만 작성 → `.claude/worktrees/`
  같은 중첩 경로가 새서 워크트리 안의 빌드 산출물(`dist/`)이 그대로 린트된다. `.gitignore`에
  있어도 ESLint/Prettier는 자동으로 읽지 않으므로 `dist/**/*`가 아니라 `**/dist/**` 처럼
  `**/` prefix를 붙여야 중첩 경로까지 잡힌다(2026-09-03, FE `pnpm check` 2,370건 중 2,366건이
  이 문제였다)

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
| 레이어 규칙·허용 예외 | §3 | 컨트롤러 레이트리밋 허용, 다른 도메인 Repository 허용(금지는 Service 순환), global → domain은 허용 3쌍만 |
| 예외 선택·code 표 | §4 | 입력 오류 `InvalidInputException`, 없음 `*NotFoundException`, 내부 불변조건 `IllegalStateException`, IAE 금지 |
| 트랜잭션 | §6 | 새 코드는 클래스 `@Transactional(readOnly = true)` + 쓰기 메서드만 `@Transactional`. rollback-only·`saveAndFlush`·AFTER_COMMIT 예외 삼킴 주의 |
| 멱등 insert + 상태 지정 API | §7-1 | `InteractionService.setPostLike` — 원하는 상태를 받아 `ON CONFLICT DO NOTHING` 또는 일괄 DELETE. 토글·`exists` 후 분기 금지 |
| 조건부 UPDATE 선점 | §7-2 | `MemberSessionRepository.consumeRefreshIfActive` — 영향 행 수(0/1)로 경쟁의 승패를 가른다 |
| upsert·원자적 카운터 | §7-3 | `FcmTokenRepository.upsertToken`, `PostRepository.incrementViewCount` |
| 일괄 DELETE | §7-4 | `deleteBy*`는 `@Modifying @Query`만(`repositoryDeleteMethodsMustBeBulk`) |
| 커밋 뒤 비동기 잡 | §7-5 | AFTER_COMMIT 리스너 → `*JobDispatcher` → self-invoke → `LambdaHandler` 잡 분기 |
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
./gradlew integrationTest  # 실제 Postgres 통합 테스트 (Docker 필요, PR CI에서 실행)
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
3. `chore(release): vX.Y.Z` 커밋 → `git push origin main`
4. **태그·GitHub Release는 수동으로 만들지 않는다** — `.github/workflows/release.yml`이 `CHANGELOG.md` push를 감지해 최신 버전 섹션을 파싱, 동명 태그가 없으면 자동으로 태그 생성 + `gh release create`까지 수행한다(이미 있으면 스킵하는 멱등 동작). `git tag`/`gh release create`를 직접 실행할 필요 없음.
- 현재 버전 기준점: `0.10.0` (정식 릴리즈 전 개발 단계 = `0.x`, `CHANGELOG.md`의 최신 버전 섹션 기준)

## 문서 파일 위치

루트에는 `README.md`·`CHANGELOG.md`만 둔다 — GitHub 생태계에서 관례적으로 루트에 두는
특수 파일(LICENSE·CONTRIBUTING과 같은 급)이고, CHANGELOG는 Keep a Changelog 스펙 자체가
루트 배치를 표준으로 규정한다. 그 외 모든 문서(배포 가이드, 아키텍처, 성능·장애 기록,
버전 호환 매트릭스 등)는 전부 `docs/`에 둔다.

`docs/` 안의 문서는 다섯 중 하나다 — **서사형**(설명, 아래 절 참고) / **절차**(how-to·런북,
예: `DEPLOY.md`·`LAMBDA-CONFIG-ROLLBACK.md`) / **레퍼런스**(예: `BE-ARCHITECTURE.md` — 버전
호환 매트릭스는 2026-09-14부로 FE 레포에 통합, 아래 보관 참고) /
**보관**(archive, 더 이상 갱신하지 않는 과거 기록 — 예: `HISTORY.md`·`VERSION-COMPATIBILITY.md`·`DEPLOY-WHEN-APP-RUNNER.md`) /
**작업 계획**(`docs/plans/<YYYY-MM-DD>-<slug>.md` — plan mode로 세운 계획의 스냅샷.
구현 코드와 같은 PR에서 커밋하고, 커밋된 뒤에는 고치지 않는다(append-only — FE
`docs/DECISIONS.md`와 같은 성격의 "무엇을 의도했는지" 기록을 이 레포에 처음 들여온
것. CI가 기존 파일 수정을 막는다). "무엇이 실제로 됐는지"는 이 파일이 아니라 PR
본문의 `## 계획 대비 구현` 섹션이 이 파일을 링크해서 대조한다).
새 문서를 쓰기 전에 어느 종류인지 먼저 정하고, 한 문서에 여러 목적을 섞지 않는다
(*Software Engineering at Google* 10장: "문서는 하나의 목적만 갖고 거기 충실해야 한다 —
API가 한 가지를 잘하듯, 한 문서에 여러 개를 담지 마라"). 전체 목록은 루트
`README.md`의 `## 문서` 섹션이 정본이다 — 새 문서를 추가하면 거기에 등록한다.

FE·BE 프로젝트 전체 길잡이(온보딩)는 FE 레포 `docs/ONBOARDING.md`가 정본이다(2026-10-02,
HISTORY·버전 매트릭스를 FE로 통합한 것과 같은 방식). 이 레포의 서사형 문서를 추가·삭제·개명하거나
도메인 패키지를 바꾸면 그 문서의 "기능 지도" 표도 FE 레포에서 함께 갱신한다 — FE의 문서 검사는 BE
경로를 검사하지 못해 자동으로 잡히지 않는다.

## 서사형 작업 문서 형식 (`docs/AI-ASYNC-PROCESSING.md`, `docs/RSS-FEED-BOT.md` 등)

"왜 만들었고 어떻게 동작하는지"를 설명하는 서사형 문서(기능 하나를 처음부터
끝까지 기록하는 문서 — CHANGELOG 항목이나 커밋 메시지로는 다 담기 힘든 배경·구조·
디버깅 과정을 남길 때 쓴다)는 아래 순서를 따른다. 기존 `docs/AI-ASYNC-PROCESSING.md`·
`docs/PERFORMANCE.md`가 "문제 → 해결 → 시행착오 → 검증" 뼈대를 각자 암묵적으로
따르고는 있었지만 명문화된 적은 없었다 — `docs/RSS-FEED-BOT.md`(2026-09-03, RSS 피드
봇 문서화 과정에서 확정, 2026-09-04 "문서만 보고 고칠 수 있는가" 리뷰를 거쳐 자기완결성
요건 추가)를 기준 예시로 아래처럼 고정한다.

**원칙**: 좋은 문서 = *독자에게 필요한 지식 − 독자가 이미 가진 지식* (Google 기술 문서
가이드). 잘 아는 사람이 쓴 문서는 모르는 상태를 상상하지 못해 맥락을 건너뛰기 쉽다("지식의
저주") — 그래서 아래 순서는 전제 지식 선언·용어 정의를 구조적으로 강제한다. 단, 이미 다른
곳에 정본이 있는 사실(DDL, 파일 트리, 코드 상수 값 등)은 옮겨적지 않고 **링크로 가리킨다**
— 복제하면 원본이 바뀔 때 문서가 조용히 거짓말하게 된다(SSOT).

모든 문서는 제목 아래 인용구(`>`) 블록으로 아래 네 가지를 번호 없이 먼저 밝힌다 —
Google 기술 문서 가이드의 audience declaration(WHO/WHAT/WHY)에 해당:

- **문서 성격** — 서사형/절차/레퍼런스/보관 중 무엇인지
- **대상 독자** — 예: "이 레포 BE를 처음 보거나 오랜만에 돌아온 개발자"
- **읽고 나면** — 이 문서만 보고 할 수 있게 되는 것 (예: "피드 소스를 추가하거나 버그를
  재현·수정할 수 있다")
- **마지막 검토**: `YYYY-MM-DD` — 문서를 고칠 때마다 갱신한다. 실제로 다시 읽고 고친
  날짜만 적는다(검토하지 않은 문서에 오늘 날짜를 넣지 않는다). 인프라·아키텍처를 바꿨으면
  관련 문서의 검토일도 함께 확인한다(Critical Rules의 "인프라·배포·아키텍처 변경 후
  문서 갱신 누락" 규칙과 같은 취지)

이어서 본문은 아래 순서를 따른다:

1. **쉬운 설명** — 핵심 개념을 이 도메인을 모르는 독자(다른 스택 개발자 등)도
   이해할 수 있는 비유로 먼저 설명. 전문 용어를 그대로 던지지 않는다. **장 끝에 전체
   프로세스 Mermaid 순서도를 반드시 넣는다** — 각 단계가 무엇을 하고 무엇을 주고받는지
   (외부 호출·DB 읽기/쓰기 등)를 노드 라벨에 적어, 글을 안 읽고 그림만 봐도 흐름이 따라와야
   한다. ```mermaid 펜스를 쓴다(GitHub이 도형으로 렌더한다). 한글 라벨에 `·`·`(`·`:`가
   섞이면 노드 텍스트를 큰따옴표로 감싸고, 줄바꿈은 `<br/>`를 쓴다
2. **전제 지식** — 이 문서가 가정하는 지식과 가정하지 않는 지식을 나누고, 가정하지 않는
   부분은 어느 문서를 먼저 보면 되는지 링크한다
3. **사용한 도구·기술** — bullet point로, "기능 자체를 이루는 것"과 "구현·검증
   과정에서 쓴 도구"를 구분해서 나열
4. **왜 만들었나** (문제)
5. **구조/아키텍처** (해결 방향) — 다이어그램·핵심 설계 결정과 그 근거. 이 장의
   다이어그램은 1번 순서도와 성격이 다르면(Lambda 경계·페이로드 등 운영 상세) ASCII로
   남겨도 되지만, 두 그림의 역할 차이를 한 줄로 밝힌다
6. **데이터 모델** — 새 테이블을 만든 기능이면 컬럼별 역할을 표로 설명한다. DDL 원문은
   옮겨적지 않고 정본 SQL 파일을 링크한다
7. **운영 파라미터** — 시간·횟수·건수 등 "언제/몇 개"에 해당하는 값은 표로 정리하고,
   각 값의 실제 위치를 명시한다(코드 상수면 `파일:줄`, 레포 밖에 있으면 그 사실과
   실제 위치 — 예: "AWS 콘솔/CLI, 어느 문서의 몇 장"). 값이 어디 있는지 얼버무리지
   않는다
8. **코드 지도와 자주 하는 수정** — 순서도 단계 ↔ 파일 매핑과 "이렇게 고치려면" 레시피
   표(각 항목에 재배포 필요 여부 명시). `README.md`에 이미 있는 파일 트리는 복제하지 않고,
   README에 없는 축(단계 ↔ 파일 매핑, 수정 레시피)으로 채운다
9. **검증 결과** — 실제로 확인한 수치·로그
10. **시행착오** — 겪은 버그와 디버깅 과정은 **뒤쪽에 배치**. 결과를 다 본 사람을
    위한 부록 성격이지, 첫 진입점이 아니다
11. **남은 것**
12. **용어 사전** — 이 문서에서 처음 나오는 고유 용어를 정의 없이 쓰지 않는다. 이미
    일반적인 개념이면 정의 대신 외부·내부 링크로 대신한다
13. **관련 문서** 링크

문서를 다 쓴 뒤에는 처음부터 훑어 다음을 확인한다 — 사람 리뷰어가 없는 1인 개발 레포에서
Google이 말하는 "독자 리뷰"(도메인을 모르는 사람이 읽어 명확성을 검증하는 것)를 스스로
대행하는 절차다: ① 정의 없이 등장하는 고유 용어가 있는가 ② 이 문서만 보고 값을 바꾸거나
고칠 수 있는가 ③ 다른 곳의 정본과 중복해서 적은 사실이 있는가.

기존 규칙(`## 릴리즈노트 관리`, 이 절 바로 위)이 CHANGELOG의 형식을 규정하는 것과
같은 이유로, 이 문서군도 매번 새로 형식을 고민하지 않도록 여기 고정해둔다.
