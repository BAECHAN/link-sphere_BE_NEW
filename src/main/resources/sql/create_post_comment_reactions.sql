-- 좋아요(reactions) 폴리모픽 구조 제거 - post_reactions / comment_reactions 로 분리
-- ddl-auto: none 이므로 수동 실행 필요
--
-- [실행 전 확인 — 필수]
--   \d comments
--   1) comments.post_id → posts(id) FK 가 ON DELETE CASCADE 인지 확인
--      (여기가 CASCADE 여야 게시글 삭제 시 comment_reactions 까지 전이 캐스케이드로 정리됨)
--   SELECT reaction_type, COUNT(*) FROM reactions GROUP BY reaction_type;
--   2) LIKE 이외의 값이 하나라도 나오면 실행을 중단하고 reaction_type 제거 결정을 재검토할 것
--      (이 마이그레이션은 좋아요 전용이라 reaction_type 컬럼을 만들지 않는다)

-- 1. 신규 테이블 (좋아요 전용 — 폴리모픽 target_id/target_type 대신 대상별 테이블 + 진짜 FK)
CREATE TABLE IF NOT EXISTS post_reactions (
    user_id    UUID      NOT NULL,
    post_id    UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_post_reactions PRIMARY KEY (user_id, post_id),
    CONSTRAINT fk_post_reactions_post
        FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_post_reactions_post_user
    ON post_reactions (post_id, user_id);

CREATE TABLE IF NOT EXISTS comment_reactions (
    user_id    UUID      NOT NULL,
    comment_id UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_comment_reactions PRIMARY KEY (user_id, comment_id),
    CONSTRAINT fk_comment_reactions_comment
        FOREIGN KEY (comment_id) REFERENCES comments (id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_comment_reactions_comment_user
    ON comment_reactions (comment_id, user_id);

-- 2. 백필 전 사전 리포트 — 버려질 row 개수를 기록해둔다 (검증 단계에서 이 숫자를 그대로 사용)
SELECT
    (SELECT COUNT(*) FROM reactions r WHERE r.target_type = 'POST'
        AND NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = r.target_id))    AS orphan_post,
    (SELECT COUNT(*) FROM reactions r WHERE r.target_type = 'COMMENT'
        AND NOT EXISTS (SELECT 1 FROM comments c WHERE c.id = r.target_id)) AS orphan_comment,
    (SELECT COUNT(*) FROM reactions r JOIN comments c ON c.id = r.target_id
        WHERE r.target_type = 'COMMENT' AND c.is_deleted = TRUE)            AS tombstone_comment;

-- 3. 백필 (재실행 안전, ON CONFLICT DO NOTHING)
--    INNER JOIN 이 고아 row 를 자동 배제한다 — FK 가 있는 상태로 고아를 INSERT 하면 거부되므로 필수.
--    톰스톤(소프트 삭제된) 댓글의 좋아요도 새 정책상 배제한다.
--    BE 배포 직후 이 블록만 한 번 더 실행할 것 — 백필~컷오버 사이 구버전이 만든 좋아요를 회수한다.
INSERT INTO post_reactions (user_id, post_id, created_at)
SELECT r.user_id, r.target_id, COALESCE(r.created_at, CURRENT_TIMESTAMP)
FROM reactions r
JOIN posts p ON p.id = r.target_id
WHERE r.target_type = 'POST'
ON CONFLICT DO NOTHING;

INSERT INTO comment_reactions (user_id, comment_id, created_at)
SELECT r.user_id, r.target_id, COALESCE(r.created_at, CURRENT_TIMESTAMP)
FROM reactions r
JOIN comments c ON c.id = r.target_id
WHERE r.target_type = 'COMMENT' AND c.is_deleted = FALSE
ON CONFLICT DO NOTHING;

-- 4. 검증 — 북마크 마이그레이션과 달리 등식이 아니다.
--    new_post = old_post - orphan_post
--    new_comment = old_comment - orphan_comment - tombstone_comment
--    (orphan_*, tombstone_comment 은 위 2번 결과값)
SELECT (SELECT COUNT(*) FROM reactions WHERE target_type = 'POST')    AS old_post,
       (SELECT COUNT(*) FROM post_reactions)                          AS new_post,
       (SELECT COUNT(*) FROM reactions WHERE target_type = 'COMMENT') AS old_comment,
       (SELECT COUNT(*) FROM comment_reactions)                       AS new_comment;

-- ─────────────────────────────────────────────────────────────
-- [롤백] BE 배포(코드 컷오버) 전이라면 새 테이블만 지우면 완전히 가역이다:
--
-- DROP TABLE IF EXISTS comment_reactions, post_reactions;
