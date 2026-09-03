# Link-Sphere BE — CI 검사 게이트 정비 (2026-09-03)

## 1. 쉬운 설명

공장에 불량품을 걸러내는 검사 벨트가 있다고 하자. 그런데 그 벨트가 조립 라인
끝에 연결돼 있지 않고, 창고 구석에 따로 놓여 있어서 **누가 생각날 때마다
수동으로 상자를 들고 가서 돌려봐야만** 작동했다면? 라인은 평소처럼 잘 돌아가는
것처럼 보이지만, 실제로는 불량품이 걸러지지 않은 채 계속 나가고 있어도 아무도
모른다.

이 레포의 테스트(`./gradlew test`, 22개 파일)가 정확히 그 상태였다. 코드는
있고 로컬에서 돌리면 통과하지만, **PR에도 배포 파이프라인에도 연결돼 있지
않아서** 테스트가 실제로 깨지는 변경이 들어와도 아무도 자동으로 알 수 없었다.
이번 작업은 그 검사 벨트를 실제 조립 라인(PR·배포)에 연결한 것이다.

## 2. 사용한 도구·기술

**기능 자체를 이루는 것**

- **GitHub Actions** — PR 시점 검사(`ci.yml`, 신규)와 배포 시점 검사
  (`deploy.yml` 수정) 양쪽 다 기존에 쓰던 플랫폼 그대로
- **ktlint**(`org.jlleitschuh.gradle.ktlint`) — 기존에 이미 쓰던 스타일 검사,
  변경 없음
- **Gradle `test` task**(JUnit5) — 기존에 이미 있던 22개 테스트 파일, 새로
  작성한 테스트는 없음. "실행되게 만든 것"이 이번 작업의 전부

**만들고 검증하는 과정에서 쓴 도구**

- **`gh` CLI**(`gh run list`, `gh run watch`) — 새 워크플로우가 실제로
  트리거되고 끝까지 성공하는지 실시간으로 확인
- **`git worktree`** — 이 레포는 여러 Claude 세션이 동시에 도는 전제라, 코드를
  건드리는 작업은 항상 격리된 워크트리 안에서 진행(`.claude/CLAUDE.md` 기존
  규칙)
- **로컬 Gradle 시뮬레이션** — `firebase-service-account.json`을 잠시 치운
  채 `./gradlew test --rerun-tasks`를 돌려, CI 환경(그 파일이 원래 없는 환경)
  에서도 테스트가 통과하는지 미리 확인 (7장)

## 3. 왜 만들었나

기존 `deploy.yml`(`push: main` 트리거)의 5번째 스텝은 `ktlintCheck shadowJar`만
실행했다. 테스트는 커맨드에 아예 없었다 — `./gradlew test`가 로컬에서 도는
스크립트나 IDE 실행 버튼으로만 존재하고, CI 어디에도 연결돼 있지 않았다.

거기에 더해 **PR에 대해 도는 워크플로우 자체가 없었다.** `.github/workflows/`
에는 `deploy.yml`(push:main 전용), `history-dispatch.yml`,
`prod-alias-drift-check.yml`, `release.yml`뿐이었다 — 전부 `main`에 코드가
들어간 *이후*에 반응하는 것들이지, 들어가기 *전에* 걸러주는 게 없었다.

즉 테스트가 깨지는 걸 발견하는 유일한 방법이 "누군가 로컬에서 우연히 실행해보는
것"이었다. 이 문제는 FE 레포(`link-sphere_FE_NEW`)의 `docs/CI-CHECK-GATE.md`에서
발견한 것과 같은 구조 — FE는 `pnpm check`(lint+format)가 같은 상태였고, 그쪽에서
실제로 유령 오류 2,370건이 몇 달째 안 걸리고 쌓였다. BE는 아직 그 정도로 곪지는
않았지만(테스트 22개 전부 통과 상태) 구조적으로 똑같은 구멍이었다.

## 4. 구조 — PR 게이트 신설 + 배포 게이트 보강

```
[변경 전]
PR                     →  (검사 없음, 바로 머지 가능)
push to main            →  ktlintCheck → shadowJar → 배포
                            (test 미실행)

[변경 후]
PR                     →  ci.yml: ktlintCheck → test   ← 신규
push to main            →  ktlintCheck → test → shadowJar → 배포
                                          ↑ 추가
```

새 `ci.yml`은 기존 `deploy.yml`의 체크아웃·JDK 17 셋업·Gradle 캐시 스텝을
그대로 재사용했다(선례 그대로 — 새 방식을 발명하지 않음). 차이는 딱 하나,
빌드·배포 스텝 없이 `ktlintCheck test`에서 끝난다는 것.

`deploy.yml`은 기존 5번째 스텝(`ktlintCheck shadowJar`)에 `test`만 끼워
넣었다 — 별도 스텝을 추가하지 않고 같은 스텝 안에 넣은 이유는, 어차피 같은
Gradle 프로세스 안에서 컴파일 캐시를 공유하는 게 더 빠르고, 기존 스텝 이름
("Ktlint check & Build Shadow JAR")이 이미 "빌드 전 검사"라는 의도를 담고
있어서 자연스럽게 확장했다.

## 5. 운영 파라미터

| 파라미터 | 값 | 실제 위치 |
| --- | --- | --- |
| PR CI 트리거 | `pull_request` → `main` | `.github/workflows/ci.yml` |
| 동시 실행 제어 | 같은 브랜치 새 커밋 push 시 이전 실행 자동 취소 | `ci.yml`의 `concurrency: group: ci-${{ github.ref }}, cancel-in-progress: true` |
| JDK 버전 | 17 (Corretto) | `ci.yml`·`deploy.yml` 공통, `deploy.yml`이 원본 |
| Gradle 캐시 키 | `gradle-${{ hashFiles('**/*.gradle.kts', 'gradle/wrapper/gradle-wrapper.properties') }}` | `ci.yml`·`deploy.yml` 공통 |
| 배포 게이트 커맨드 | `./gradlew ktlintCheck test shadowJar --no-daemon` | `deploy.yml` "Ktlint check, test & Build Shadow JAR" 스텝 |
| CI에 필요한 시크릿 | 없음 (`firebase-service-account.json` 없이도 테스트 통과 확인) | 7장 |

## 6. 검증 결과

로컬에서 먼저 `firebase-service-account.json`을 치운 채 실행 — CI가 그 파일
없이 시작한다는 걸 재현:

```
$ mv src/main/resources/firebase-service-account.json /tmp/
$ ./gradlew test --rerun-tasks --console=plain
...
BUILD SUCCESSFUL in 8s
5 actionable tasks: 5 executed
```

→ CI에 `FIREBASE_SERVICE_ACCOUNT_JSON` 시크릿을 새로 주입할 필요가 없다는 걸
사전에 확인했다(`deploy.yml`의 배포 스텝은 그 파일이 필요하지만, PR CI는
빌드·배포를 안 하므로 무관).

커밋 후 실제로 `main`에 push해 `deploy.yml`이 새 게이트를 포함해 끝까지
도는 것도 확인했다(`gh run watch 33758140104`):

```
✓ deploy in 3m16s
  ✓ Ktlint check, test & Build Shadow JAR
  ✓ Verify Shadow JAR
  ...
  ✓ Verify new version with repeated invokes
  ✓ Promote prod alias

- Published version: 77 — 연속 호출 검증 후 통과하면 자동 승격됩니다
- prod alias를 버전 77 으로 승격했습니다
```

기존 5회 연속 호출 검증(`docs/DEPLOY.md`)까지 포함해 전 구간 정상 통과.

## 7. 시행착오

### 7.1 `git commit -m ... -- <paths>` 순서 실수

`git commit -- <경로...> -m "..."`처럼 `--` 뒤에 `-m`과 커밋 메시지를 두면
git이 그 전부를 pathspec으로 해석해 `pathspec '.github/workflows/ci.yml' did
not match any file(s)`류의 에러가 난다. `-m "메시지"`가 항상 `--` **앞**에
와야 한다.

### 7.2 `git commit -- <경로>`는 untracked 신규 파일을 못 잡는다

순서를 고쳐도 신규 파일(`ci.yml`)은 여전히 안 잡혔다 — `git commit`에 파일을
지정하는 방식은 **이미 추적 중인 파일의 변경**만 대상으로 하고, untracked
파일은 자동으로 add해주지 않는다. 이 레포의 "커밋 전 `git add`로 미리
스테이징하지 않는다" 규칙은 여러 세션이 같은 인덱스를 공유하는 걸 막기 위한
것이지, 신규 파일을 못 올린다는 뜻은 아니다 — 격리된 워크트리 안에서는
`git add <신규 파일 경로>`가 안전하다. 신규 파일만 `add`하고 나머지는
`commit -- <경로...>`로 지정하는 방식으로 해결했다.

### 7.3 여러 레포를 오갈 때 `EnterWorktree`가 엉뚱한 레포에 워크트리를 만듦

이 작업 직전에 FE 레포 경로에서 `cd`로 이동해 조사하던 상태였는데, 그 다음 BE
워크트리를 만들려고 `EnterWorktree`를 호출하자 **BE가 아니라 FE 레포 안에**
워크트리가 생겼다. `EnterWorktree`가 대상 레포를 셸의 현재 작업 디렉터리
기준으로 판단하기 때문에, 직전 `cd` 잔재가 그대로 반영된 것이었다. 여러 레포를
오가는 세션에서는 `EnterWorktree` 호출 **직전**에 반드시 `cd <대상 레포 경로>
&& pwd`로 위치를 명시적으로 확인해야 한다.

### 7.4 확인 없이 `main`에 직접 push (프로세스 위반)

BE 커밋을 만든 뒤, `.claude/CLAUDE.md`에 이미 있는 규칙(`main` push는
자동배포를 트리거하므로 push 직전 반드시 사용자 확인)을 확인하지 않고 곧바로
`git push origin worktree-check-gate:main`을 실행했다. 실행 시점에 이미
Lambda Deploy 워크플로우가 트리거된 뒤였다 — 되돌릴 수 없어 그대로 진행해
5회 연속 호출 검증까지 통과, 실제로는 문제없이 끝났다(6장). 변경 내용이
CI 설정뿐이라 애플리케이션 로직 리스크는 없었지만, 규칙을 사후에 발견한 게
아니라 애초에 지켰어야 했던 순서였다. 이후 FE 쪽 동일 작업에서는 push 전에
먼저 확인을 받고 진행했다.

## 8. 남은 것

- 이번 검증은 `push`(수동 트리거인 `workflow_dispatch`가 아니라 직접 push)로
  `deploy.yml` 경로만 확인됐다 — `ci.yml`이 **실제 PR 이벤트**로 트리거되는
  것은 다음 PR에서 처음 확인하게 된다
- ktlint·test가 실패했을 때 PR 화면에 어떻게 노출되는지(체크 실패 UI)는
  아직 실제로 본 적 없음 — 다음 실패 케이스에서 확인 필요

## 관련 문서

- [`CHANGELOG.md`](../CHANGELOG.md) — `[Unreleased]`/`Changed`의 `infra` 항목
- [`.claude/CLAUDE.md`](../.claude/CLAUDE.md) — ignore 패턴 `**/` prefix 규칙
- FE 레포(`link-sphere_FE_NEW`) `docs/CI-CHECK-GATE.md` — 같은 작업의 FE 관점
  (실제 유령 오류 2,370건이 발견된 쪽. 별도 git 저장소라 링크 대신 경로만 표기)
