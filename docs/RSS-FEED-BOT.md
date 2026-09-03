# Link-Sphere BE — RSS 피드 자동 수집 봇 (2026-09-03)

## 1. 문제

Link-Sphere는 사용자가 링크를 직접 등록해야만 피드가 채워진다. 서비스 초기라
콘텐츠가 비어 있으면 신규 방문자에게 보여줄 게 없고, 기존 사용자도 다시 들어올
이유가 없다.

봇 계정 `링크봇`이 큐레이션된 RSS/Atom 피드를 매일 자동 수집해 공개 게시글로
등록하도록 했다. 등록된 글은 사람이 올린 글과 완전히 동일한 경로 — SSRF 검증,
크롤링, AI 요약·태그·카테고리 자동 분류 — 를 그대로 거친다.

수집 소스는 임의 사이트 스크래핑이 아니라 RSS/Atom으로 한정했다. RSS는 발행자가
배포를 명시적으로 허용한 채널이라, 같은 목적을 저작권 문제 없이 달성한다.

## 2. 해결 방향 — 기존 AI 비동기 처리와 동일한 self-invoke 구조

피드 9개를 fetch하는 것과 새 글을 하나하나 크롤링하는 것을 한 번의 Lambda 호출
안에서 다 처리하면 120초 타임아웃을 넘긴다(`docs/AI-ASYNC-PROCESSING.md`가 같은
제약으로 self-invoke를 도입한 선례). 그래서 동일한 shape로 쪼갰다.

```
EventBridge cron(0 22 * * ? *)   # UTC 22:00 = KST 07:00
        │  {"linksphereJob":"feed-crawl"}
[Stage A] FeedCrawlService.collectAndDispatch()
  · feed_sources(enabled=true) 순회, 소스당 최대 2건 / 전체 최대 15건 fetch
  · FeedUrlNormalizer로 정규화 → feed_items에 이미 있는 URL 제외
  · 남은 항목을 5건씩 chunk → chunk마다 self-invoke
        │  {"linksphereJob":"feed-item","event":{"items":[…5건…]}}
[Stage B] FeedCrawlService.processFeedItemJob(event)
  · 항목마다 독립 트랜잭션 — feed_items claim → PostService.createPost(botId, ...)
        │
[Stage C] "ai-analysis"  ← 기존 경로, 코드 변경 없음
```

### 2.1 chunk를 5건으로 자른 이유

신규 URL을 하나씩 개별 Lambda 호출로 넘기면(예: 15건 → 15개 동시 실행):

- Supabase 트랜잭션 풀러에 15개 클라이언트가 동시 접속한다(컨테이너마다 Hikari
  풀이 별개라 격리되지 않는다).
- 게시글 15개가 거의 동시에 커밋돼 AI 요약 잡도 15번 거의 동시에 발사된다 —
  Gemini 분당 호출 제한(RPM)을 곧바로 초과해 요약이 실패한다.

`chunked(5)` 한 줄로 동시 실행이 3개(15÷5)로 줄고, chunk 내부는 순차 커밋이라
AI 잡도 시간축에 자연스럽게 퍼진다. 부수 효과: chunk가 타임아웃돼 Lambda가 자동
재시도해도, 이미 claim된 항목은 즉시 skip되어 남은 항목만 이어서 처리된다.

5라는 값 자체에 특별한 공식은 없다 — "감당 가능한 동시성" 수준으로 잡은 값이고,
배포 후 `ai_status = FAILED` 비율이 높으면(Gemini RPM 초과 신호) 3으로 낮추도록
`docs/DEPLOY.md` 8장에 적어뒀다.

## 3. 중복 방지 — `posts.url`을 건드리지 않은 이유

봇이 같은 글을 매일 다시 수집하면 안 되지만, 기존 게시글 URL 컬럼(`posts.url`)에는
unique 제약을 걸지 않았다:

1. **실 DB에 이미 중복이 있었다.** 확인 쿼리(`SELECT url, COUNT(*) ... HAVING COUNT(*) > 1`)
   실행 결과 스택오버플로 링크 1건이 7명에게 등록돼 있는 등 5건이 나왔다 —
   제약을 걸면 마이그레이션 자체가 즉시 실패했을 것이다.
2. **서비스 본질을 깬다.** A와 B가 같은 좋은 글을 각자 등록하는 건 정상 동작이고
   `PostService.createPost`가 지금 이를 허용한다.

대신 봇 전용 원장 테이블 `feed_items`를 새로 만들어 **거기에만** unique를 걸었다
(정규화된 URL 기준, `sql/create_feed_sources.sql`). 사람 사용자의 동작은 한 줄도
바뀌지 않는다. `post_id`는 nullable + `ON DELETE SET NULL`로 둬서, 봇 글을
관리자가 지워도 원장은 남아 재수집되지 않는다.

## 4. 시행착오 — `attachPost`가 flush 없이 실행되던 문제

### 4.1 증상

로컬 E2E 검증(`FeedCrawlRunner --commit`) 첫 실행에서 후보 14건이 **전부** 실패했다:

```
ERROR: insert or update on table "feed_items" violates foreign key constraint "fk_feed_items_post"
  Detail: Key (post_id)=(6b4dd5f2-...) is not present in table "posts".
```

`feed_items`/`posts` 카운트를 다시 확인해보니 둘 다 0 — claim한 원장 행까지
포함해 트랜잭션 전체가 롤백돼 있었다.

### 4.2 원인

`FeedItemProcessor.processFeedItem`은 한 트랜잭션 안에서 (1) `PostService.createPost`로
새 `TablePost`를 만들고, (2) `FeedItemRepository.attachPost`(`@Modifying` JPQL
`UPDATE`)로 방금 만든 postId를 `feed_items`에 채워 넣는다.

문제는 `@Modifying` 쿼리가 JDBC로 **직접** 나간다는 것이다 — Hibernate의
영속성 컨텍스트(dirty-checking 기반 flush 큐)를 거치지 않는다. `createPost`가
만든 `TablePost`의 실제 `INSERT` 문은 (1)의 `save()` 호출 시점이 아니라 다음
flush 시점까지 지연돼 있었는데, (2)의 `attachPost`가 그 flush보다 먼저 DB에
도달해 존재하지 않는 `post_id`를 참조하는 `UPDATE`를 실행한 것이다.

### 4.3 수정

```kotlin
@Modifying(flushAutomatically = true)
@Query("UPDATE TableFeedItem f SET f.postId = :postId WHERE f.id = :id")
fun attachPost(@Param("id") id: UUID, @Param("postId") postId: UUID)
```

`flushAutomatically = true`가 이 `UPDATE`를 실행하기 직전에 영속성 컨텍스트를
강제로 flush시켜, 대기 중이던 `Post` INSERT가 먼저 DB에 반영되게 한다.

### 4.4 검증

수정 후 재실행 — 14건 전부 성공. 곧바로 같은 잡을 한 번 더 실행해 멱등성도
확인했다: 기존 URL은 정상적으로 skip되고, 그사이 GeekNews에 새로 올라온 글
1건만 추가로 처리됐다(`totalElements` 14 → 15).

이 버그는 로컬에서만 재현된 게 아니다. 만약 발견 못 하고 그대로 배포했다면,
실제 Lambda 환경에서도 동일한 순서로 같은 예외가 나 **봇 글이 하나도 등록되지
않았을 것**이다 — Lambda self-invoke의 트랜잭션 경계와 `@Modifying` 쿼리의
flush 미보장이 겹치는, 로컬/운영 환경 차이가 아니라 순수하게 코드 로직의 문제였다.

## 5. 검증 (실제 프로덕션)

배포 후 EventBridge 룰을 만들기 전, prod Lambda에 Stage A를 직접 트리거했다:

```bash
aws lambda invoke --function-name link-sphere-api:prod --log-type Tail \
  --payload fileb://feed-event.json out.json --query 'LogResult' --output text | base64 -d
```

```
2026-09-03T08:48:55.129Z WARN  [FeedCrawl] 피드 fetch 실패 - 우아한형제들 기술블로그: HTTP 403
2026-09-03T08:48:58.037Z INFO  [FeedCrawl] 후보가 모두 이미 수집된 URL - 종료
REPORT Duration: 4136.29 ms  Billed Duration: 4137 ms  Memory Size: 2048 MB
```

- 4.1초 만에 완료 (90초 deadline guard, 120초 Lambda 타임아웃에 여유)
- 우아한형제들만 크롤러 UA를 차단(403) — 소스별 `runCatching` 격리 덕에 나머지
  8개 소스는 영향 없음
- 로컬 검증 때 만든 15건이 실제 운영 환경에서도 전부 정상적으로 중복 제외됨
  (`feed_items`/봇 게시글 카운트 그대로 15/15 유지)

## 6. 남은 것

- 네이버 D2 피드(`enabled=false`로 시딩)의 실제 접근 가능 여부 미확인
- GeekNews 항목 링크가 원문이 아니라 토론 페이지(`news.hada.io/topic?id=...`)인
  점 — 그대로 둘지는 실제 등록 결과를 더 보고 판단
- 우아한형제들 기술블로그 403 — 필요하면 User-Agent 조정 검토 (지금은 실패해도
  다른 소스에 영향 없어 낮은 우선순위)
- 다음 EventBridge 자동 실행: 2026-09-04 07:00 KST — 신규 게시글이 실제로
  올라오는지 재확인 필요

## 관련 문서

- [`docs/DEPLOY.md`](./DEPLOY.md) 8장 — EventBridge 룰 설정 절차
- [`docs/AI-ASYNC-PROCESSING.md`](./AI-ASYNC-PROCESSING.md) — 이번에 재사용한
  self-invoke 패턴의 원형
- [`CHANGELOG.md`](../CHANGELOG.md) — 버전별 변경 요약
