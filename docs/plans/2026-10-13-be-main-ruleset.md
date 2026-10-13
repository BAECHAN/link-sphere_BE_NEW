# BE main에 기본 보호 규칙셋 추가 (삭제·강제 push 금지)

> BE `main`에 FE와 같은 기본 규칙(브랜치 삭제 금지, 강제 push 금지)을 건다. 필수 체크는 넣지 않는다(결정 1~3에서 따로 정함).

## Context

⑥ 실측(2026-10-13, claude-base `docs/RESEARCH.md` §7.5)에서 BE에는 규칙셋도 클래식 브랜치 보호도 없다는 것을 확인했다
(`gh api repos/BAECHAN/link-sphere_BE_NEW/rulesets` → 0개, `…/rules/branches/main` → 0개). 지금은 실수 한 번으로
main 이력이 바뀌거나 브랜치가 지워질 수 있다. 예를 들어 PR 수정에 쓰는 "amend 후 `--force-with-lease`"를 main에서 실행하는 경우다.
그러면 배포·릴리즈 태그의 기준이 흔들린다. 사용자가 결정 4를 먼저 하기로 했다(2026-10-13).

- FE 선례: 규칙셋 "Main Branch Protect"(id 23733613) — `deletion`, `non_fast_forward`, 비어 있는 `required_status_checks`, 우회 없음.
- BE에서 main에 직접 push하는 자동화는 없다. `history-dispatch.yml`은 FE로 dispatch만 보내고(`contents: read`), `release.yml`은
  태그만 push한다. `.github`·`.claude/CLAUDE.md`에 main 강제 push를 쓰는 곳도 없다.

## 판단이 필요했던 항목

| 항목 | 결정 | 근거·기각한 대안 |
| --- | --- | --- |
| 규칙 | `deletion` + `non_fast_forward` 두 개 | 사용자 결정(결정 4). FE의 빈 `required_status_checks` 규칙은 아무것도 막지 않아 넣지 않는다. 필수 체크는 결정 1~3에서 다룬다 |
| 대상 | `~DEFAULT_BRANCH`(main) | FE 선례와 같다 |
| 우회 | 없음 | FE 선례와 같다. BE에는 main 직접 push 봇이 없어 필요한 예외가 없다. 정말 강제 push가 필요하면 규칙셋을 잠깐 끈다 |
| 이름 | `Main Branch Protect` | FE와 같은 이름이라 두 레포에서 찾기 쉽다 |
| 동작 검증 | main으로는 시험하지 않는다. 같은 규칙셋 대상에 `refs/heads/probe/nff`를 **잠깐** 추가해 그 브랜치로 강제 push·삭제가 막히는지 보고, 대상을 원래대로 되돌린 뒤 브랜치를 지운다 | main으로 시험하면 규칙이 안 먹을 때 main이 실제로 바뀐다 |
| 기록 | BE `docs/CI-CHECK-GATE.md` §5 운영 파라미터에 "main 브랜치 규칙셋" 행 추가(PR) | BE CLAUDE.md "인프라 변경 후 문서 갱신". 규칙셋은 코드가 아니라 GitHub 설정이라 문서에 확인 명령을 함께 적는다 |

## 세부 계획

| 순서 | 내용 |
| --- | --- |
| 1 | `gh api -X POST repos/BAECHAN/link-sphere_BE_NEW/rulesets`로 규칙셋 생성: `name: Main Branch Protect`, `target: branch`, `enforcement: active`, `conditions.ref_name.include: ["~DEFAULT_BRANCH"]`, `rules: [deletion, non_fast_forward]`, `bypass_actors: []` |
| 2 | 확인: `gh api …/rules/branches/main`에 `deletion`·`non_fast_forward`가 보이는지 |
| 3 | 동작 실측: main SHA에서 서로 갈라진 빈 커밋 두 개를 `git commit-tree`로 만든다(작업 트리·index 변경 없음). 첫 커밋으로 `probe/nff` 브랜치를 API로 만들고, 규칙셋 `include`에 `refs/heads/probe/nff`를 더한다. ① 두 번째 커밋으로 강제 push(`--force-with-lease`) → 거절(`GH013`) 예상 ② 브랜치 삭제(API `DELETE git/refs`) → 거절 예상 |
| 4 | 정리: 규칙셋 `include`를 `["~DEFAULT_BRANCH"]`로 되돌리고 `probe/nff`를 지운다. `gh api …/rulesets/<id>`의 `conditions`가 원래대로인지, `git ls-remote --heads origin 'probe/*'`가 비었는지 확인 |
| 5 | BE 워크트리(`EnterWorktree` + 부트스트랩)에서 `docs/CI-CHECK-GATE.md` §5에 행 추가: "main 브랜치 규칙셋 \| `Main Branch Protect`(삭제 금지·강제 push 금지, 우회 없음, 2026-10-13) \| GitHub 설정 — `gh api repos/BAECHAN/link-sphere_BE_NEW/rulesets`". 마지막 검토는 이미 2026-10-13. 계획 스냅샷 `docs/plans/2026-10-13-be-main-ruleset.md` |
| 6 | 커밋(`chore(infra): …` 또는 선례에 맞는 타입), push, PR. fresh Explore 서브에이전트로 계획 대비 구현 대조(BE §8). CI 확인 → 사용자 승인 뒤 머지 |

## 영향 범위 (§5)

- **평소 흐름**: PR squash 머지는 main 위에 새 커밋을 얹는 것이라 `non_fast_forward`에 걸리지 않는다. 머지된 PR 브랜치 자동 삭제
  (`delete_branch_on_merge`)는 main이 아니라 영향이 없다. Dependabot PR, 태그 push(`release.yml`), dispatch(`history-dispatch.yml`)도 그대로다.
- **막히는 것**: main 강제 push·main 삭제. 관리자 계정(= Claude가 쓰는 권한)에도 적용된다.
- **실측 중 잠깐 대상이 넓어지는 것**: `probe/nff` 하나만 추가했다가 되돌린다. main 규칙은 그대로다.
- **문서**: BE `docs/CI-CHECK-GATE.md`. FE 규칙셋은 문서에 기록돼 있지 않지만 이번 범위가 아니라 "남은 것"에 적는다.

## 검증 방법

1. 2번의 effective rules 응답에 두 규칙이 있다.
2. 3번 실측: 강제 push와 삭제가 각각 `GH013`/거절 응답이 난다(원문 기록).
3. 4번 정리 후 규칙셋 `conditions.ref_name.include`가 `["~DEFAULT_BRANCH"]`뿐이고 `probe/*` 브랜치가 없다.
4. 문서 PR의 CI 통과(`gh pr checks`). 머지 뒤 BE main push 워크플로는 경로 필터 때문에 돌지 않는 것이 정상이다(⑤와 같음).

## 남은 것

- FE 규칙셋("Main Branch Protect")도 문서에 기록돼 있지 않다. 결정 1~3을 할 때 함께 기록한다.
- 결정 1~3(필수 체크, 히스토리 봇, 관리자 예외)은 별도로 진행한다. 결정 3 전에 `gh pr merge`의 자동 우회 여부를 실측해야 한다.
