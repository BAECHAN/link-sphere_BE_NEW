package com.example.linksphere.global.exception

/** retryAfterSeconds는 응답의 Retry-After 헤더(RFC 9110 §10.2.3, 초 단위)로 나간다. */
class RateLimitExceededException(message: String, val retryAfterSeconds: Long? = null) : RuntimeException(message)
