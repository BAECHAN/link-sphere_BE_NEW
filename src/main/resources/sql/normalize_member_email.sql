-- 회원가입 이메일 대소문자/공백 정규화 - ddl-auto: none 이므로 수동 실행 필요
--
-- 2026-08-11 실DB 점검 결과 대상 0행(전 회원 이미 소문자·공백 없음). 향후 유입 대비 +
-- 재실행해도 안전하도록(멱등) 남겨둔다.
-- members.email에는 지금까지 유니크 제약이 전혀 없었다(기존 인덱스는 PK뿐) - 동시 가입
-- 요청이 겹치면 같은 이메일 계정이 실제로 두 개 생길 수 있었고, 그 경우 findByEmail이
-- IncorrectResultSizeDataAccessException을 던져 해당 이메일 로그인이 영구히 깨진다.

-- 1. 기존 값 정규화
UPDATE members SET email = lower(btrim(email)) WHERE email <> lower(btrim(email));

-- 2. 대소문자 무시 유니크 인덱스 - 저장을 소문자로 정규화하므로 평범한 UNIQUE(email)로도
--    충분하지만, 정규화를 우회하는 경로(직접 SQL 등)까지 막기 위해 함수형 인덱스로 건다.
CREATE UNIQUE INDEX IF NOT EXISTS members_email_lower_key ON members (lower(email));
