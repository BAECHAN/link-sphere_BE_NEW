-- 회원가입 닉네임 대소문자 무시 유니크화 - ddl-auto: none 이므로 수동 실행 필요
--
-- 2026-08-11 실DB 점검에서 발견: existsByNickname이 대소문자를 구분해 'Tester02'(2026-04-19
-- 가입) 위에 'tester02'(2026-06-12 가입)가 그대로 가입됐다. 둘 다 tester.com 테스트 계정이며
-- 게시글·댓글·북마크·좋아요·FCM 토큰 등 활동이 0건이라 개명 대신 계정 자체를 삭제해 정리한다.
-- (삭제 전 두 행 전체를 로컬에 백업해뒀다.)

-- 1. 기존 중복 정리 - 활동 없는 테스트 계정 2건 삭제
DELETE FROM members WHERE id IN (
    '60a213fb-e363-49bf-a772-10a389d412df',  -- Tester02, 2026-04-19
    'ad253a15-2ec1-40f8-a4c4-10a3fccc2fbf'    -- tester02, 2026-06-12
);

-- 2. 대소문자 무시 유니크 인덱스 - 표시용 대소문자는 그대로 저장하고 비교만 강제한다.
--    nickname은 nullable이지만 Postgres는 NULL끼리 충돌시키지 않으므로 안전하다.
CREATE UNIQUE INDEX IF NOT EXISTS members_nickname_lower_key ON members (lower(nickname));
