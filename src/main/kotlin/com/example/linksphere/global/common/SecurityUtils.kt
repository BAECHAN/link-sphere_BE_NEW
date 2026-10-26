package com.example.linksphere.global.common

import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import java.util.UUID

// 파일 최상위 함수라 클래스가 없다 - 다른 로거와 같이 패키지를 포함한 이름을 쓴다(JSON 로그의 logger_name)
private val logger = LoggerFactory.getLogger("com.example.linksphere.global.common.SecurityUtils")

fun Authentication?.getUserId(): UUID? {
    if (this == null) return null
    return try {
        UUID.fromString(name)
    } catch (e: Exception) {
        logger.warn("Failed to parse UUID from auth name: $name")
        null
    }
}

// SessionAuthenticationFilter가 details에 실어둔 회전 계열 id. FCM 토큰을 세션에
// 묶을 때(FcmTokenController)만 쓴다 - details가 없으면(예: 테스트에서 직접 만든
// Authentication) null.
fun Authentication?.getSessionFamilyId(): UUID? = this?.details as? UUID
