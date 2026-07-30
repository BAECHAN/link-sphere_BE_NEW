-- Stage 2: 북마크 다중 폴더 소속 - 구 컬럼 정리 (Phase C, 파괴적)
-- ddl-auto: none 이므로 수동 실행 필요
--
-- ⚠️ 이 파일은 배포가 완료되고 아래가 전부 검증된 뒤에만 실행한다:
--   1. create_bookmark_folder_items.sql 의 백필이 끝나고 검증 SELECT 두 값이 일치
--   2. BE + FE 배포 완료, bookmarkFolderIds/폴더 소속 API 정상 동작 확인
--   3. src/main/resources/sql/create_bookmark_folder_items.sql 헤더의 수동 검증 4항목 통과
--
-- 되돌릴 수 없다 — bookmarks.folder_id 는 이 시점부터 어떤 코드도 참조하지 않으므로
-- 롤백이 필요하면 이 파일을 실행하기 전 상태로 돌아가야 한다.

ALTER TABLE bookmarks DROP CONSTRAINT IF EXISTS fk_bookmarks_folder;
DROP INDEX IF EXISTS idx_bookmark_user_folder;
ALTER TABLE bookmarks DROP COLUMN IF EXISTS folder_id;
