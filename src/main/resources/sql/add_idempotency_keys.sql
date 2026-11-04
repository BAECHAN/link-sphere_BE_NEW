-- 글·댓글·메일 요청의 중복 방지(docs/plans/2026-11-04-idempotent-create.md).
-- 브라우저가 만든 Idempotency-Key를 행에 함께 저장하고, (회원, 키) 유니크 인덱스로 같은 요청의 재전송을 막는다.
-- 기존 행과 봇이 만든 행은 키가 NULL이다 - Postgres 유니크 인덱스는 NULL끼리 같다고 보지 않아 충돌하지 않는다.
--
-- 실행: 세션 풀러 5432로, BE 배포 전에 실행한다. BE가 먼저 나가면 posts·comments 조회가 전부 500이다.
-- lock_timeout: ALTER TABLE이 긴 트랜잭션 뒤에 줄을 서면 뒤따르는 조회까지 막힌다. 3초 안에 잠금을 못 잡으면 실패하니 다시 실행한다.
-- 표마다 트랜잭션을 나눠 잠금을 짧게 잡는다. 이미 끝난 표가 있으면 그 블록은 건너뛰고 실행한다.

BEGIN;
SET LOCAL lock_timeout = '3s';
ALTER TABLE posts ADD COLUMN idempotency_key uuid;
CREATE UNIQUE INDEX uq_posts_user_idempotency_key ON posts (user_id, idempotency_key);
COMMIT;

BEGIN;
SET LOCAL lock_timeout = '3s';
ALTER TABLE comments ADD COLUMN idempotency_key uuid;
CREATE UNIQUE INDEX uq_comments_user_idempotency_key ON comments (user_id, idempotency_key);
COMMIT;

BEGIN;
SET LOCAL lock_timeout = '3s';
ALTER TABLE member_action_tokens ADD COLUMN idempotency_key uuid;
CREATE UNIQUE INDEX uq_member_action_tokens_member_idempotency_key ON member_action_tokens (member_id, idempotency_key);
COMMIT;
