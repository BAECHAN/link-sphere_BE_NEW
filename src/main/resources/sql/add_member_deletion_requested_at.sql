-- 회원탈퇴 14일 유예기간 - members 컬럼 추가
-- ddl-auto: none 이므로 수동 실행 필요
-- 반드시 BE 코드 배포 "전"에 실행할 것 - 컬럼이 없으면 TableMember 매핑이 깨져
-- 모든 member SELECT(로그인 포함)가 즉시 실패한다(add_member_auth_columns.sql과 같은 경고).

-- 1. 탈퇴 신청 시각. 채워지면 유예 중(다른 사용자에게는 탈퇴한 것처럼 보이지만, 이
--    시각으로부터 14일 안에 로그인하면 취소된다). deleted_at은 그대로 "실제로 익명화가
--    끝났다"는 의미를 유지한다 - 이미 탈퇴 완료된 계정(deleted_at만 있고 이 컬럼은 null)이
--    아래 부분 인덱스로 하는 만료 조회에 걸리지 않게 하기 위함이다.
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS deletion_requested_at TIMESTAMPTZ NULL;

-- 2. 매일 실행되는 만료 회원 조회(AccountPurgeService)가 이 인덱스를 탄다. 이미 익명화가
--    끝난 회원(deleted_at IS NOT NULL)은 부분 인덱스 조건에서 제외해 크기를 줄인다.
CREATE INDEX IF NOT EXISTS idx_members_deletion_pending
    ON members (deletion_requested_at)
    WHERE deletion_requested_at IS NOT NULL AND deleted_at IS NULL;
