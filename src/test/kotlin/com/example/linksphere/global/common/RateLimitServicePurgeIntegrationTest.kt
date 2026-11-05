package com.example.linksphere.global.common

import com.example.linksphere.domain.auth.AuthRateLimitRepository
import com.example.linksphere.domain.auth.TableAuthRateLimit
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.Instant

// 삭제 쿼리의 경계를 실제 Postgres로 본다. RateLimitServiceTest는 기준 시각 전달만 mock으로 확인한다.
@PostgresIntegrationTest
@Import(RateLimitService::class)
class RateLimitServicePurgeIntegrationTest {

    @Autowired private lateinit var rateLimitService: RateLimitService

    @Autowired private lateinit var repository: AuthRateLimitRepository

    @Test
    fun `purgeExpired는 하루가 지난 창만 지우고 경계와 최근 창은 남긴다`() {
        val now = Instant.parse("2026-11-05T00:00:00Z")
        val cutoff = now.minus(Duration.ofDays(1))
        repository.saveAndFlush(TableAuthRateLimit(bucketKey = "old", windowStart = cutoff.minusSeconds(1), hitCount = 1))
        repository.saveAndFlush(TableAuthRateLimit(bucketKey = "boundary", windowStart = cutoff, hitCount = 1))
        repository.saveAndFlush(TableAuthRateLimit(bucketKey = "recent", windowStart = now, hitCount = 1))

        assertEquals(1, rateLimitService.purgeExpired(now))

        assertEquals(setOf("boundary", "recent"), repository.findAll().map { it.bucketKey }.toSet())
    }
}
