# BE detekt 도입 검토 — 보류하고 기록만 보강

<!-- plan-result:start — 이 블록만 병합 뒤에도 고칠 수 있다(CLAUDE.md §8). 아래는 승인된 계획 원문 -->

## 구현 결과

> #200 · 2026-11-15 대조 · 이탈 1 · 미구현 0 — 판정 근거(파일:줄)는 PR 본문 "계획 대비 구현"

- 이탈(규칙 적용): PR 본문에 계획이 적은 `## 의도 대비 결과` 대신 `## 계획 대비 구현`을 넣었다. plan mode를 거친 작업이라 BE CLAUDE.md §8이 그 절을 요구하는데, 계획을 쓸 때 이를 반영하지 않았다.

<!-- plan-result:end -->

## Context

사용자 질문: "detekt도 우리 BE에 필요할까? 업계 표준은 이런 도구를 권하는가?"
BE는 ktlint(스타일·포맷)와 ArchUnit(참조 규칙)만 쓴다. ESLint에 가까운 "버그·냄새 패턴" 검사는 비어 있다.
2026-10-26 BE #113 `docs/BE-ARCHITECTURE.md` "도구 선택" 표(831줄)에서 detekt를 ❌로 둔 적이 있다. 다만 그때는
아키텍처 규칙 도구로만 비교했다.
2026-11-15 사용자가 질문 카드에서 "보류+기록 보강"을 골랐다. 이번 작업은 표의 Detekt 행 이유만 보강하는 BE 문서 PR 하나다.

## 확인한 사실 (조사 결과, 행 내용의 근거)

| 항목 | 내용 | 확인 수준 |
|---|---|---|
| detekt 안정판 | 1.23.8, Kotlin 2.0.21 기준 | [호환 표](https://detekt.dev/docs/introduction/compatibility/), 에이전트가 읽음 |
| detekt 2.0 | alpha.6(2026-08-04)까지, Kotlin 2.4.10 기준, 정식판 없음 | [릴리스](https://github.com/detekt/detekt/releases), 에이전트가 읽음 |
| 우리 BE | Kotlin 2.4.21, Gradle 9.8.2 | `build.gradle.kts`, `gradle-wrapper.properties` 직접 읽음 |
| 컴파일러 경고 | 4건(main 2, test 2) | BE PR CI run 43211692762 로그 grep |
| Kotlin 공식 | 도구 설정법만 안내하고 "권장"이라고 하지 않음 | [jvm-code-analysis](https://kotlinlang.org/docs/jvm-code-analysis.html), 에이전트가 읽음 |
| Allegro 2026-03 | detekt 비교 뒤 "ktlint 유지" 권고, 특정 문제에 한정해 쓰면 좋다 | [블로그](https://blog.allegro.tech/2026/03/static-code-analysis-kotlin.html), 에이전트가 읽음 |
| Google SWE 20장 | 리뷰 검사 효과적 오탐 10% 미만, 무시되는 경고는 오류로 올리거나 숨김 | [ch20](https://abseil.io/resources/swe-book/html/ch20.html), 에이전트가 읽음 |

## 할 일

1. BE 워크트리를 만든다. FE 세션이므로 `git -C <BE> worktree add`로 만들고 cd한다(메모리 "FE 세션에서 BE 워크트리").
   만들기 전에 `git -C <BE> log origin/main..main`으로 로컬에만 있는 커밋이 없는지 본다.
   → verify: `git worktree list`에 새 경로가 보인다.
2. `docs/BE-ARCHITECTURE.md` "도구 선택" 표의 Detekt 행 "이유" 칸을 아래 내용으로 바꾼다. 다른 행과 표 구조는 건드리지 않는다.
   - 아키텍처 규칙(2026-10-26)뿐 아니라 버그·냄새 패턴 검사 용도로도 검토했다(2026-11-15).
   - 안정판 1.23.8은 Kotlin 2.0.21 기준, 2.0은 alpha(2.4.10 기준)뿐이다. 이 레포는 2.4.21이다(호환 표·릴리스 링크).
   - Allegro(2026-03)는 비교 끝에 ktlint 유지를 권했다. 번역 인용 + 링크(§10 형식).
   - **재검토 조건:** detekt 정식판이 이 레포의 Kotlin 버전을 지원하고, 이 레포에 같은 종류의 버그가 반복될 때.
   - 머리말 "마지막 검토" 줄을 2026-11-15로 고치고, 고친 범위를 앞 항목들과 같은 형식으로 적는다.
   → verify: `git diff`가 그 행과 "마지막 검토" 줄만 바꿨는지 본다.
3. `.gitmessage`를 읽고 그 형식으로 `docs(arch): …` 커밋 → push → PR. `docs/`만 바꾸므로 deploy.yml 경로 필터(`src/**` 등)에 걸리지 않는다. 배포는 돌지 않는다.
   PR 본문에는 `## 의도 대비 결과`를 넣는다. 사용자 결정(보류+기록)과 판정을 적는다.
   → verify: `gh pr checks`에서 CI·serving-path가 green인지 본다.
4. squash 병합한다(`gh pr merge --squash`). 병합 확인 뒤 워크트리와 로컬 브랜치를 정리한다(규칙: 병합 확인 시 바로 정리).

## 하지 않는 것

- detekt·allWarningsAsErrors 설정 추가, 컴파일러 경고 4건 수정(사용자가 고르지 않음)
- FE 문서 변경(FE는 BE 도구 표를 복제하지 않는다)

## 검증 방법

- PR diff가 Detekt 행 1줄과 "마지막 검토" 줄만 바꿨다.
- PR CI(ci.yml·serving-path)가 green이다.
- 병합 커밋이 origin/main에 있고, 워크트리가 정리됐다.

<!-- no-diagram: 문서 한 행 수정이라 분기·실패 지점이 있는 흐름이 없다 -->
