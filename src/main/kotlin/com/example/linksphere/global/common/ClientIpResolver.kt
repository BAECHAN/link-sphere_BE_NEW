package com.example.linksphere.global.common

import jakarta.servlet.http.HttpServletRequest

/**
 * CloudFront가 오리진에 심어주는 CloudFront-Viewer-Address 헤더("ip:port" 또는 IPv6는
 * "[::1]:port" 형식)에서 실제 요청자 IP만 뽑아낸다. SecureToken과 같은 이유로 상태 없는
 * object로 둔다 - DI가 필요 없는 순수 함수다.
 *
 * 이 헤더는 CloudFront 오리진 요청 정책에 명시적으로 추가해야만 전달된다(docs/DEPLOY.md
 * §5-2 참고) - 아직 설정 전이거나 Function URL을 직접 두드린 요청(Phase 0 가드가 이미 막지만
 * EventBridge 워밍 핑 등 합성 이벤트는 그 가드 자체를 우회)이면 헤더가 없어 null을 반환한다.
 * 호출부는 null을 "IP를 특정할 수 없음"으로 처리하고 그 축의 레이트리밋만 건너뛴다 - 헤더
 * 하나 누락으로 로그인·가입 자체가 막히는 것보다 안전하다.
 */
object ClientIpResolver {
    private const val HEADER_NAME = "CloudFront-Viewer-Address"

    fun resolve(request: HttpServletRequest): String? {
        val value = request.getHeader(HEADER_NAME) ?: return null
        return if (value.startsWith("[")) {
            value.substringBefore("]").removePrefix("[")
        } else {
            value.substringBeforeLast(":")
        }
    }
}
