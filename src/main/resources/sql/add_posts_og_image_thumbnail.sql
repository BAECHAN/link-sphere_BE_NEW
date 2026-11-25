-- 피드 카드용 og 이미지 사본 - posts 컬럼 추가
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 BE 코드 배포 "전"에 실행할 것 - 컬럼이 없으면 TablePost 매핑이 깨져 posts를 읽는 API(목록·상세·등록)가 전부 실패한다
-- (add_bookmark_folders_pinned_at.sql과 같은 경고). 운영에는 2026-11-25에 실행했다.

-- 서버가 og_image를 받아 줄여 FE 버킷(og-thumbnails/)에 올린 사본의 절대 주소. null이면 사본이 없어 FE가 og_image를 그린다.
-- og_image가 바뀌면 같은 트랜잭션에서 null로 비우고, 사본 잡이 다시 채운다(OgThumbnailService). 조회 조건에 쓰는 것은
-- 백필 한 곳뿐이라 인덱스는 두지 않는다.
ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS og_image_thumbnail TEXT NULL;
