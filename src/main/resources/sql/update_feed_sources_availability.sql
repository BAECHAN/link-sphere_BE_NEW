-- 피드 소스 가용성 재확인에 따른 활성화 상태 조정 (2026-09-04, Lambda 실측 기준).
-- 근거·전체 소스별 가능/불가능 표는 docs/RSS-FEED-BOT.md 참고.

-- 우아한형제들 기술블로그: 피드 fetch 자체가 Lambda(AWS IP 대역)에서 403 - 항목을
-- 하나도 못 가져오므로 비활성화한다.
UPDATE feed_sources SET enabled = FALSE WHERE url = 'https://techblog.woowahan.com/feed/';

-- 네이버 D2: create_feed_sources.sql 시딩 당시 "접근 가능 여부 미확인"으로 enabled=false였다.
-- 재확인 결과 피드 fetch·항목 크롤링 모두 정상이라 활성화한다.
UPDATE feed_sources SET enabled = TRUE WHERE url = 'https://d2.naver.com/d2.atom';
