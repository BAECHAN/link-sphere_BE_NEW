-- og:image 상대경로 백필 - ddl-auto: none 이므로 수동 실행 필요
--
-- UrlMetadataExtractor가 og:image를 baseUri 기준으로 절대화하기 전에는 루트 상대경로
-- ("/img/thumb.png" 등)나 빈 문자열을 그대로 저장했다. FE가 루트 상대경로를 자기
-- 도메인 기준으로 요청해 CloudFront/S3 403을 유발한다(예: /img/apple_icon.png). 이런
-- 값은 지금도 렌더에 실패해 화면에는 이미 안 보이므로(빈 문자열은 LinkThumbnail이
-- falsy로 걸러 애초에 요청 자체를 안 보냄), NULL로 비워도 사용자가 보는 결과는 그대로이고
-- 잘못된 요청만 사라진다. 2026-09-06 실DB 점검 기준 posts 6건(빈 문자열 4 + 루트
-- 상대경로 2), comments 0건.
--
-- 프로토콜-상대경로("//host/path")는 대상에서 제외한다 - 브라우저가 현재 프로토콜
-- 기준으로 올바르게(예: https://i.namu.wiki/...) 해석해 우리 도메인으로 요청하지
-- 않으므로 이번 버그와 무관하고, 외부 CDN 핫링크 차단(namu.wiki 등)은 FE
-- LinkThumbnail의 onError 폴백이 이미 처리한다.
--
-- 썸네일을 되살리려면 해당 글의 URL을 수정해 재크롤링해야 한다(6건이라 자동 백필
-- 도구는 만들지 않음). 재실행해도 안전하다(멱등).

UPDATE posts SET og_image = NULL
WHERE og_image IS NOT NULL
  AND og_image NOT LIKE 'http://%'
  AND og_image NOT LIKE 'https://%'
  AND og_image NOT LIKE '//%';

UPDATE comments SET link_og_image = NULL
WHERE link_og_image IS NOT NULL
  AND link_og_image NOT LIKE 'http://%'
  AND link_og_image NOT LIKE 'https://%'
  AND link_og_image NOT LIKE '//%';
