package com.example.linksphere.global.exception

// InvalidTokenException(세션 refresh 전용, 응답 코드가 INVALID_REFRESH_TOKEN으로 고정)과는
// 별개 - 비밀번호재설정·이메일인증 토큰(member_action_tokens)에 재사용하면 응답 코드가
// 의미상 틀어진다.
class InvalidActionTokenException(message: String) : RuntimeException(message)
