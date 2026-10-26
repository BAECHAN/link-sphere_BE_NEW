-- 북마크 폴더 고정 - bookmark_folders 컬럼 추가
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 BE 코드 배포 "전"에 실행할 것 - 컬럼이 없으면 TableBookmarkFolder 매핑이 깨져
-- 폴더 목록·생성·이름 변경 등 bookmark_folders를 읽는 API가 전부 실패한다(add_member_deletion_requested_at.sql과 같은 경고).

-- 고정한 시각. null이면 고정하지 않은 폴더다. FE의 고정 구획은 이 시각 오름차순이다.
-- created_at·updated_at과 같은 timestamp without time zone이다. 목록은 회원의 폴더를 전부 읽어 FE가 고르므로 인덱스는 두지 않는다.
ALTER TABLE bookmark_folders
    ADD COLUMN IF NOT EXISTS pinned_at TIMESTAMP NULL;
