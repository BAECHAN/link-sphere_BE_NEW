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
