-- Stage 2: 북마크 다중 폴더 소속 - 스키마 생성
-- ddl-auto: none 이므로 수동 실행 필요
--
-- [실행 전 확인 — 필수]
--   \d bookmarks
--   1) (user_id, post_id) 에 PRIMARY KEY 또는 UNIQUE 인덱스가 있어야 아래 복합 FK 생성 가능
--   2) post_id → posts(id) FK 가 ON DELETE CASCADE 인지 확인 (없으면 파일 하단 참고)

-- 1. 복합 FK 대상 유니크 (membership.user_id == folder.user_id 를 DB가 보장하게 만든다)
ALTER TABLE bookmark_folders
    DROP CONSTRAINT IF EXISTS uk_bookmark_folders_user_id;
ALTER TABLE bookmark_folders
    ADD CONSTRAINT uk_bookmark_folders_user_id UNIQUE (user_id, id);

-- 2. 소속 테이블 (bookmark ↔ folder N:M)
CREATE TABLE IF NOT EXISTS bookmark_folder_items (
    user_id    UUID      NOT NULL,
    post_id    UUID      NOT NULL,
    folder_id  UUID      NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_bookmark_folder_items
        PRIMARY KEY (user_id, post_id, folder_id),
    -- 북마크 해제(bookmarks row 삭제) 시 소속 전부 자동 삭제
    CONSTRAINT fk_bookmark_folder_items_bookmark
        FOREIGN KEY (user_id, post_id) REFERENCES bookmarks (user_id, post_id)
        ON DELETE CASCADE,
    -- 폴더 삭제 시 그 폴더의 소속만 삭제 (북마크 자체는 유지 = 다른 폴더에 남아있거나 미분류)
    CONSTRAINT fk_bookmark_folder_items_folder
        FOREIGN KEY (user_id, folder_id) REFERENCES bookmark_folders (user_id, id)
        ON DELETE CASCADE
);

-- 3. 인덱스
--    bookmark → folders 방향: PK 프리픽스 (user_id, post_id) 가 이미 커버 (추가 불필요)
--    folder → bookmarks 방향 + GROUP BY folder_id + 폴더 삭제 캐스케이드 조회용:
CREATE INDEX IF NOT EXISTS idx_bookmark_folder_items_folder
    ON bookmark_folder_items (folder_id, post_id);

-- 4. 백필 — 기존 단일 폴더 지정을 소속 row 로 이전 (재실행 안전, ON CONFLICT DO NOTHING)
--    BE 배포 직후에도 한 번 더 실행할 것 — 백필~컷오버 사이 구버전이 만든 지정을 회수한다.
INSERT INTO bookmark_folder_items (user_id, post_id, folder_id, created_at)
SELECT b.user_id, b.post_id, b.folder_id, COALESCE(b.created_at, CURRENT_TIMESTAMP)
FROM bookmarks b
WHERE b.folder_id IS NOT NULL
ON CONFLICT DO NOTHING;

-- 5. 검증 (두 값이 같아야 한다)
SELECT (SELECT COUNT(*) FROM bookmarks WHERE folder_id IS NOT NULL) AS old_rows,
       (SELECT COUNT(*) FROM bookmark_folder_items)                 AS new_rows;

-- ─────────────────────────────────────────────────────────────
-- [선택] 1번 확인에서 bookmarks.post_id → posts(id) FK 가 없다고 나오면,
-- 고아 row 정리 후 아래를 실행한다 (고아가 있으면 FK 생성이 거부된다):
--
-- DELETE FROM bookmarks b WHERE NOT EXISTS (SELECT 1 FROM posts p WHERE p.id = b.post_id);
-- ALTER TABLE bookmarks
--     ADD CONSTRAINT fk_bookmarks_post FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE CASCADE;
