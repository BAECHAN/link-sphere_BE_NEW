-- 북마크 게시글 목록 "최근 열람순" 정렬 - 스키마 생성
-- ddl-auto: none 이므로 수동 실행 필요

-- 1. post_views 테이블 (user_id + post_id 당 행 하나, 볼 때마다 viewed_at 갱신 — upsert)
CREATE TABLE IF NOT EXISTS post_views (
    user_id    UUID      NOT NULL,
    post_id    UUID      NOT NULL,
    viewed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_post_views
        PRIMARY KEY (user_id, post_id),
    -- 게시글 삭제 시 열람 기록도 함께 삭제 (고아 row 방지)
    -- user_id는 다른 테이블(bookmark_folders 등)과 동일하게 members FK 없이 둔다
    CONSTRAINT fk_post_views_post
        FOREIGN KEY (post_id) REFERENCES posts (id)
        ON DELETE CASCADE
);

-- 2. 정렬 쿼리(user_id 필터 + viewed_at 정렬)용 인덱스
CREATE INDEX IF NOT EXISTS idx_post_views_user_viewed
    ON post_views (user_id, viewed_at);
