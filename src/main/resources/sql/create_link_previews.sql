-- 글 등록 전 링크 미리보기 결과 캐시(작성 중 미리보기 → 등록 시 재사용)
-- ddl-auto: none 이므로 수동 실행 필요 - 이 테이블을 쓰는 코드보다 먼저 배포 전에 실행한다

-- url_hash는 sha256(url) hex. 미리보기에서 크롤링한 결과를 10분간(LinkPreviewService.FRESH_FOR)
-- 등록·수정이 그대로 재사용한다 - 사용자가 본 미리보기와 저장되는 글이 같아지고, 등록 때
-- 크롤링(약 2.7초)을 다시 하지 않는다. page_content는 AI 요약 재료라 응답에는 싣지 않는다.
-- 오래된 행은 정리하지 않는다(URL당 1행, auth_rate_limits와 같은 정책).
CREATE TABLE IF NOT EXISTS link_previews (
    url_hash     VARCHAR(64) NOT NULL,
    url          TEXT        NOT NULL,
    title        TEXT        NOT NULL,
    description  TEXT,
    og_image     TEXT,
    tags         TEXT[],
    page_content TEXT,
    fetched_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_link_previews PRIMARY KEY (url_hash)
);

CREATE INDEX IF NOT EXISTS idx_link_previews_fetched_at ON link_previews (fetched_at);
