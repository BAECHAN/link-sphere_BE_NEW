package com.example.linksphere.global.config.security

import jakarta.servlet.Filter

/**
 * 요청마다 "누가 보냈는지"를 판정해 SecurityContext에 싣는 필터. `SecurityConfig`가 이 타입으로 받아 필터 체인에 끼우고,
 * 구현은 인증을 맡은 도메인이 한다(`domain.auth.SessionAuthenticationFilter`) — global이 도메인의 구체 타입을 모르게 한다.
 *
 * 구현이 지킬 계약: 토큰이 만료·무효면 예외를 던지지 않고 `request.setAttribute("exception", "TOKEN_EXPIRED" | "INVALID_TOKEN")`만
 * 남긴다. `SecurityConfig`의 인가 규칙과 `CustomAuthenticationEntryPoint`가 이 값을 읽어 401 코드를 고른다.
 * 구현 빈은 하나여야 한다 — 둘이면 `SecurityConfig` 주입이 모호해 시작할 때 실패한다.
 */
interface RequestAuthenticationFilter : Filter
