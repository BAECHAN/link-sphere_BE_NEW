-- Stage 1: 북마크 폴더 관리 - 스키마 생성
-- ddl-auto: none 이므로 수동 실행 필요

-- 1. bookmark_folders 테이블 생성
CREATE TABLE IF NOT EXISTS bookmark_folders (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_bookmark_folders_user_name UNIQUE (user_id, name)
);

CREATE INDEX IF NOT EXISTS idx_bookmark_folders_user_sort
    ON bookmark_folders (user_id, sort_order);

-- 2. bookmarks 테이블에 folder_id 컬럼 추가 (NULL = 미분류)
ALTER TABLE bookmarks
    ADD COLUMN IF NOT EXISTS folder_id UUID NULL;

-- 3. FK 제약: 폴더 삭제 시 안의 북마크는 미분류(NULL)로 이동
ALTER TABLE bookmarks
    DROP CONSTRAINT IF EXISTS fk_bookmarks_folder;
ALTER TABLE bookmarks
    ADD CONSTRAINT fk_bookmarks_folder
    FOREIGN KEY (folder_id) REFERENCES bookmark_folders(id)
    ON DELETE SET NULL;

-- 4. 폴더별 북마크 조회 가속용 인덱스
CREATE INDEX IF NOT EXISTS idx_bookmark_user_folder
    ON bookmarks (user_id, folder_id);
