package com.example.linksphere.global.common

import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import java.util.UUID

private val logger = LoggerFactory.getLogger("SecurityUtils")

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
