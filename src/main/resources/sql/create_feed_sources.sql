-- RSS 피드 자동 수집(봇 계정) - 스키마 생성
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 BE 코드 배포 "전"에 실행할 것 — TableMember.isBot 필드가 매핑된 상태로
-- is_bot 컬럼이 없으면 모든 member SELECT(로그인 포함)가 즉시 실패한다.
--
-- [실행 전 확인 — 필수]
--   같은 URL을 여러 사용자가 각자 등록한 경우가 있는지 확인한다 (posts.url에는
--   unique를 걸지 않으므로 이 마이그레이션과는 무관하지만, 설계 근거 기록용):
--   SELECT url, COUNT(*) FROM posts GROUP BY url HAVING COUNT(*) > 1 ORDER BY 2 DESC LIMIT 20;

-- 1. 봇 계정 식별 컬럼
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS is_bot BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. 봇 계정 1행
--    password는 BCrypt 해시 형식이 아닌 값을 넣어 matches()가 항상 false가 되게 한다
--    (로그인 자체를 봉쇄 — 별도 잠금 컬럼 없이 가장 단순하게 막는 방법).
--    nickname은 members_nickname_lower_key 유니크 인덱스를 타므로 기존 닉네임과 겹치면 안 된다.
--    email의 실제 유니크 인덱스는 lower(email) 표현식 인덱스(members_email_lower_key)라
--    ON CONFLICT 대상도 반드시 lower(email)로 맞춰야 한다 (plain email로는 매칭되지 않음).
INSERT INTO members (id, email, password, nickname, is_bot, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'bot@link-sphere.local',
    '!',
    '링크봇',
    TRUE,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
)
ON CONFLICT (lower(email)) DO NOTHING;

-- 3. RSS/Atom 피드 소스 목록
CREATE TABLE IF NOT EXISTS feed_sources (
    id              UUID         PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    url             TEXT         NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    last_fetched_at TIMESTAMP    NULL,
    last_error      TEXT         NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_feed_sources_url UNIQUE (url)
);

-- 4. 수집 이력 원장 — 중복 등록 방지 전용. posts.url에는 절대 unique를 걸지 않는다
--    (사람 사용자가 같은 URL을 각자 등록하는 것은 정상 동작이고, 기존 중복 데이터가
--    있으면 그 자체로 마이그레이션이 실패한다). post_id를 ON DELETE SET NULL로 두어
--    관리자가 봇 글을 지워도 원장은 남고, 다음 날 같은 URL이 재수집되지 않게 한다.
CREATE TABLE IF NOT EXISTS feed_items (
    id              UUID      PRIMARY KEY,
    source_id       UUID      NULL,
    post_id         UUID      NULL,
    normalized_url  TEXT      NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_feed_items_normalized_url UNIQUE (normalized_url),
    CONSTRAINT fk_feed_items_source
        FOREIGN KEY (source_id) REFERENCES feed_sources (id) ON DELETE SET NULL,
    CONSTRAINT fk_feed_items_post
        FOREIGN KEY (post_id) REFERENCES posts (id) ON DELETE SET NULL
);

-- 5. 피드 소스 시딩 (2026-09-03 실제 fetch로 생존 확인, 국내 개발·기술 블로그 중심)
--    네이버 D2는 접근 가능 여부를 확인하지 못해 enabled=false로 시딩한다 — 확인 후
--    UPDATE feed_sources SET enabled = TRUE WHERE name = '네이버 D2'; 로 켠다.
--    소스 추가/제거는 이 테이블에 INSERT/UPDATE 한 줄로 한다 (재배포 불필요).
INSERT INTO feed_sources (id, name, url, enabled) VALUES
    (gen_random_uuid(), 'GeekNews', 'https://news.hada.io/rss/news', TRUE),
    (gen_random_uuid(), '우아한형제들 기술블로그', 'https://techblog.woowahan.com/feed/', TRUE),
    (gen_random_uuid(), '토스 테크', 'https://toss.tech/rss.xml', TRUE),
    (gen_random_uuid(), '카카오 기술블로그', 'https://tech.kakao.com/feed/', TRUE),
    (gen_random_uuid(), 'LY Corporation Tech', 'https://techblog.lycorp.co.jp/ko/feed/index.xml', TRUE),
    (gen_random_uuid(), '당근 기술블로그', 'https://medium.com/feed/daangn', TRUE),
    (gen_random_uuid(), '하이퍼커넥트 기술블로그', 'https://hyperconnect.github.io/feed.xml', TRUE),
    (gen_random_uuid(), '요즘IT', 'https://yozm.wishket.com/magazine/feed/', TRUE),
    (gen_random_uuid(), '네이버 D2', 'https://d2.naver.com/d2.atom', FALSE)
ON CONFLICT (url) DO NOTHING;
