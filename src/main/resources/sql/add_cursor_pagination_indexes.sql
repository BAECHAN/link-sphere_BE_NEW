-- 목록 cursor 전환용 정렬 키 인덱스 - ddl-auto: none 이므로 수동 실행 필요
-- 이 SQL을 쓰는 코드보다 먼저, 세션 풀러(5432)에서 실행한다. 인덱스 없이 배포해도 결과는 같지만 정렬 키를 처음부터 훑는다.
-- CONCURRENTLY를 쓰지 않는다 - 지금 표가 작아(글 수백 건) 쓰기를 막는 시간이 짧고, add_member_deletion_requested_at.sql과 같은 형태다.
-- 큰 표에서는 쓰기를 막지 않도록 CONCURRENTLY로 바꿔 트랜잭션 밖에서 실행한다(docs/PAGINATION.md §6).

-- 1. 피드 최신순. 이 쪽의 마지막 글 다음부터 바로 읽는다.
CREATE INDEX IF NOT EXISTS idx_posts_created_at_id
    ON posts (created_at DESC, id DESC);

-- 2. 북마크 최신순·오래된순(같은 인덱스를 거꾸로 읽는다). 회원별로 읽는다.
CREATE INDEX IF NOT EXISTS idx_bookmarks_user_created_at_post
    ON bookmarks (user_id, created_at DESC, post_id DESC);

-- 3. 내 댓글 최신순. 지금은 user_id로 시작하는 인덱스가 없어 표 전체를 훑는다.
CREATE INDEX IF NOT EXISTS idx_comments_user_created_at_id
    ON comments (user_id, created_at DESC, id DESC);

-- 4. 정렬 키가 NULL이면 비교에서 빠져 그 글을 다시는 읽지 못한다. 운영의 NULL은 0건이고(2026-11-05 확인),
--    DEFAULT now()와 TablePost 기본값이 있어 새 글에도 생기지 않는다.
ALTER TABLE posts ALTER COLUMN created_at SET NOT NULL;
