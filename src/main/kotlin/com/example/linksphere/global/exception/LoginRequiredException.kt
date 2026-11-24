package com.example.linksphere.global.exception

// 토큰 없이 와도 되는 공개 엔드포인트가, 로그인한 사람 기준으로만 뜻이 있는 요청을 비로그인으로 받았을 때 던진다.
// 보안 진입점(CustomAuthenticationEntryPoint)의 "토큰 없음"과 같은 401 NOT_LOGGED_IN으로 나간다.
class LoginRequiredException(message: String = "Authentication required") : RuntimeException(message)
