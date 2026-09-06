# 문서 안내

> **문서 성격**: 랜딩 페이지 — 아래 문서들을 요약하지 않고 안내만 한다.
>
> **대상 독자**: 이 레포에서 무언가를 찾는 모든 개발자.
>
> **읽고 나면**: 지금 필요한 문서가 어느 것인지 찾아 이동할 수 있다.
>
> **마지막 검토**: 2026-09-06

`docs/`의 문서는 성격에 따라 네 종류로 나뉜다. 새 문서를 추가하면 이 표와 루트
[`../README.md`](../README.md)의 `## 문서` 섹션 두 곳 모두에 등록한다
(`.claude/CLAUDE.md`의 "문서 파일 위치" 규칙).

## 서사형 (설명 — "왜 만들었고 어떻게 동작하는지")

| 문서 | 무엇을 설명하는가 |
| --- | --- |
| [`RSS-FEED-BOT.md`](./RSS-FEED-BOT.md) | RSS 피드 자동 수집 봇 — 발견(RSS)·등록(크롤링)·분석(AI) 3단계 구조 |
| [`AI-ASYNC-PROCESSING.md`](./AI-ASYNC-PROCESSING.md) | 게시글 AI 분석을 비동기(Lambda self-invoke)로 뺀 이유와 구조 |
| [`CI-CHECK-GATE.md`](./CI-CHECK-GATE.md) | ktlint·테스트를 PR·배포 파이프라인의 실제 게이트로 정비한 과정 |
| [`PERFORMANCE.md`](./PERFORMANCE.md) | Lambda 콜드스타트 원인 분석과 최적화 근거 |

## 절차 (how-to·런북 — "이럴 땐 이렇게 한다")

| 문서 | 언제 보는가 |
| --- | --- |
| [`DEPLOY.md`](./DEPLOY.md) | 배포 인프라 설정·변경, EventBridge 룰 조작, GitHub Actions 배포 흐름 |
| [`LAMBDA-CONFIG-ROLLBACK.md`](./LAMBDA-CONFIG-ROLLBACK.md) | `prod` alias를 안전 버전으로 롤백해야 할 때 |

## 레퍼런스 (사실 — "지금 값이 뭔가")

| 문서 | 무엇을 담고 있는가 |
| --- | --- |
| [`VERSION-COMPATIBILITY.md`](./VERSION-COMPATIBILITY.md) | BE·FE 버전 호환 매트릭스 — 어느 FE 버전이 어느 BE 버전을 요구하는지 |

## 보관 (archive — 더 이상 갱신하지 않음)

| 문서 | 비고 |
| --- | --- |
| [`HISTORY.md`](./HISTORY.md) | 2026-08-01부터 갱신 중단. 이후 이력은 FE 레포에서 통합 관리 |
| [`DEPLOY-WHEN-APP-RUNNER.md`](./DEPLOY-WHEN-APP-RUNNER.md) | App Runner로 운영하던 이전 배포 방식 (현재는 Lambda) |

## 문서가 아닌 것

- [`../README.md`](../README.md) — 프로젝트 개요, 기술 스택, API 엔드포인트, 시작하기
- [`../CHANGELOG.md`](../CHANGELOG.md) — 버전별 변경 사항 (Keep a Changelog)
