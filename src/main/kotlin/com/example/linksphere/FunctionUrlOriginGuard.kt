package com.example.linksphere

import java.security.MessageDigest

/**
 * Lambda Function URL이 CloudFront 없이 직접 호출되는 것을 막는 임시 잠금(Phase 0).
 * 진짜 잠금(OAC 전환)은 별도 라운드에서 다룬다 - docs/plans/2026-09-28-auth-hardening.md 참고.
 *
 * CloudFront 오리진 설정에서 X-Origin-Verify 커스텀 헤더를 붙이도록 구성했다는 전제로,
 * 그 값이 ORIGIN_VERIFY_SECRET 환경변수와 일치하는지만 확인한다 - CloudFront를 거치지 않고
 * Function URL을 직접 두드리면 이 헤더가 없으므로 걸러진다.
 */
object FunctionUrlOriginGuard {
    const val HEADER_NAME = "X-Origin-Verify"

    /**
     * domainName이 비어있으면 EventBridge 워밍 핑·CI 5-invoke 게이트처럼 requestContext를
     * 합성한 내부 호출이라는 뜻이라 검사 자체를 건너뛴다 - 둘 다 .lambda-url. 도메인이 없는
     * 최소 payload를 쓴다(docs/DEPLOY.md 6·8장, .github/workflows/deploy.yml 참고). 실제
     * Function URL을 거친 요청(CloudFront 경유든 직접 호출이든)은 AWS가 이 필드를 항상
     * 채워 넣으므로 외부 호출자가 이 필드를 지워서 검사를 우회할 방법은 없다.
     *
     * secret이 비어있으면(아직 환경변수를 안 넣은 배포 과도기) 막지 않고 통과시킨다
     * (fail-open) - 이 잠금 자체가 새로 추가되는 것이라, 설정 누락으로 API 전체가 막히는
     * 것보다는 지금 수준(공개 상태 유지)이 더 안전하다.
     */
    fun isAllowed(domainName: String?, headerValue: String?, secret: String?): Boolean {
        if (domainName.isNullOrBlank() || !domainName.contains(".lambda-url.")) return true
        if (secret.isNullOrBlank()) return true
        if (headerValue.isNullOrBlank()) return false
        return MessageDigest.isEqual(secret.toByteArray(), headerValue.toByteArray())
    }
}
