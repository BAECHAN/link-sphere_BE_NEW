package com.example.linksphere.domain.comment

/**
 * 댓글 등록·답글·수정이 공유하는 입력 한도. CommentService가 검사하고, CreateCommentRequest가 같은 값을 OpenAPI 스펙에
 * 싣는다(FE는 손으로 둔 상수를 스펙에서 생성한 상수와 테스트로 대조한다 - docs/BE-ARCHITECTURE.md §7-12).
 */
object CommentLimits {

    const val MAX_IMAGES = 5

    // UTF-8 바이트 기준. CloudFront WAF(AWSManagedRulesCommonRuleSet의 SizeRestrictions_BODY,
    // 기본값 그대로 유지 - 커스텀 크기 룰은 CloudFront Pro 플랜 전용이라 이 계정에서 못 만든다)가
    // 요청 바디 8,192바이트 초과를 403(HTML)으로 차단하므로, 앱이 먼저 400으로 되돌려 사용자가
    // 이유를 알 수 있게 한다 - WAF 차단은 앱 에러 처리를 전혀 타지 못한다. 6,000바이트는 이미지
    // URL 5개(~650B)·JSON 봉투를 더해도 8,192B 벽에 여유 있게 들어가도록 잡은 값이다.
    // 스펙에는 x-max-bytes 확장으로 실린다(JSON Schema에 바이트 키워드가 없다). FE는 이 값을 손으로 둔
    // MAX_COMMENT_CONTENT_BYTES와 테스트로 대조한다.
    const val MAX_CONTENT_BYTES = 6_000
}
