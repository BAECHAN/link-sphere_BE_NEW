# BE PR CI의 base 브랜치 필터 제거

> BE `ci.yml`의 `pull_request.branches: ['main']`을 없애, base가 main이 아닌 스택 PR에서도 CI가 돌게 한다.
> FE는 같은 사각지대를 2026-09-16(#111)에 실측하고 고쳤다.

<!-- no-diagram: 트리거 한 줄과 문서 한 줄을 바꾸는 단일 단계 작업이라 흐름이 없다 -->

## Context

link-sphere 후속 작업 ⑤(claude-base 3라운드 후속 목록 `docs/plans/2026-10-13-round3.md:225`).

- BE `.github/workflows/ci.yml:6-8`은 `pull_request: branches: ['main']`이다. GitHub Actions는 `branches`를 주면
  그 브랜치를 base로 하는 PR에서만 워크플로를 실행한다. 다른 PR 브랜치를 base로 하는 스택 PR에서는 ktlint·테스트가
  돌지 않는다.
- FE `ci.yml:9-17`은 같은 필터를 #111(2026-09-16)에서 없앴다. 이유는 PR #109가 #108 브랜치를 base로 열려 있는 동안
  자동 CI가 0건이었기 때문이다(FE 주석 그대로).
- BE에서 base가 main이 아닌 PR은 지금까지 0건이다(`gh pr list --state all --limit 200`). 실제 사고는 없었고,
  FE와 맞추는 예방 조치다.
- BE의 다른 `branches` 필터(`deploy.yml:6`, `history-dispatch.yml:8`, `release.yml:9`)는 `push: main` 트리거라 대상이 아니다.

## 판단이 필요했던 항목

| 항목 | 결정 | 근거·기각한 대안 |
| --- | --- | --- |
| 필터 처리 | `branches` 줄을 지우고, FE와 같은 취지의 이유 주석을 단다 | FE 선례 #111. "docs/plans 불변성 확인" 스텝은 이미 `github.base_ref`를 동적으로 써서(`ci.yml:29`) 스택 PR에서도 맞게 동작한다 |
| `concurrency` | 그대로(`ci-${{ github.ref }}`) | PR마다 `github.ref`가 `refs/pull/N/merge`라 스택 PR끼리 섞이지 않는다 |
| 실제 동작 검증 | 이 PR 브랜치를 base로 하는 임시 스택 PR(빈 커밋)을 열어 CI가 도는지 보고, 확인 뒤 닫고 임시 브랜치를 지운다 | `pull_request` 이벤트는 PR 머지 ref의 워크플로 파일로 트리거를 판단하므로, 이 PR이 머지되기 전에도 새 설정으로 확인할 수 있다(검증 결과로 확인). 문서만 근거로 두는 안은, 실측이 가능한데 하지 않는 것이라 기각 |
| CHANGELOG | 갱신 안 함 | `ci:` 커밋(FE·BE 선례에 항목 없음) |

## 세부 계획

| 위치 | 변경 내용 |
| --- | --- |
| BE `.github/workflows/ci.yml:6-8` | `branches: ['main']` 삭제, `pull_request:` 위에 이유 주석(스택 PR 사각지대, FE #111 선례, base_ref 동적 참조) |
| BE `docs/CI-CHECK-GATE.md:85` | "PR CI 트리거" 값을 "`pull_request`(base 브랜치 필터 없음 — 스택 PR도 트리거, 2026-10-13)"로. 마지막 검토 날짜 갱신 |
| BE `docs/plans/2026-10-13-ci-base-filter.md` (신규) | 이 계획 스냅샷(BE §8 규칙) |

진행 순서(BE 규칙)
1. BE 메인 체크아웃에서 `git log origin/main..main`(미푸시 확인) → `EnterWorktree` → BE 부트스트랩
   (`application-secret.yml`, `firebase-service-account.json` 복사).
2. 수정 → YAML 파싱 확인 → `.gitmessage` 형식으로 커밋(`ci(ci): …`) → push → PR.
3. fresh Explore 서브에이전트로 계획 대비 구현을 대조한다(BE `.claude/CLAUDE.md` §8). 결과는 PR 본문 `## 계획 대비 구현`에 적는다.
4. 임시 스택 PR로 트리거를 검증하고 정리한다.

## 영향 범위 (§5)

- **CRUD**: 해당 없음.
- **기존 동작**: main을 base로 하는 PR의 CI는 그대로다. 달라지는 점은 main이 아닌 base의 PR에서도 CI가 돈다는 것뿐이다
  (지금까지 0건이라 Actions 사용량 변화는 사실상 없다).
- **배포**: `deploy.yml`은 `push: main` + 경로 필터(`src/**` 등)라, 이 PR 머지로는 배포가 돌지 않을 수 있다.
  머지 뒤 run 목록을 확인하고, 안 돌았으면 그 사실을 보고한다(워크플로 파일만 바뀌어 배포할 것이 없다).
- **문서**: BE `docs/CI-CHECK-GATE.md`. FE 문서에는 BE CI 트리거 서술이 없다(grep으로 구현 때 다시 확인).

## 검증 방법

1. `ci.yml` YAML 파싱(`node -e` + `yaml` 패키지, 또는 `python3 -c 'import yaml'`), `pull_request`에 `branches`가 없는지.
2. 이 PR(base main)의 CI가 통과하는지 `gh pr checks`로 확인한다.
3. **스택 PR 실측**: 이 PR 브랜치를 base로 하는 임시 브랜치에 빈 커밋을 올려 draft PR을 연다. 그 PR에 CI run이 생기는지
   `gh run list --branch <임시 브랜치>`로 확인한다. 확인 뒤 PR을 닫고 원격 임시 브랜치를 지운다. 경위는 PR 본문에 적는다.
4. 머지는 승인 뒤. 머지 뒤 `push: main` 워크플로 run(배포가 경로 필터로 안 돌면 그 사실 포함)을 확인하고 보고한다.

## 남은 것

- ⑥ GitHub 필수 체크 선행 조건 확인(히스토리 봇·릴리즈 직접 push의 우회 가능 여부)은 별도 계획으로 진행한다.
