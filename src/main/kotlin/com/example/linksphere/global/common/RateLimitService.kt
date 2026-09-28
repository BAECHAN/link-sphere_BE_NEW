package com.example.linksphere.global.common

import com.example.linksphere.domain.auth.AuthRateLimitRepository
import com.example.linksphere.global.exception.RateLimitExceededException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 고정 윈도(fixed window) 카운터 기반 레이트리밋. bucketKey·limit·window 외엔 아무 도메인
 * 지식이 없는 범용 유틸이다 - 지금은 auth_rate_limits 테이블(Phase 2, domain/auth 소유)이
 * 유일한 저장소라 Repository는 그쪽에서 가져오지만, 이 서비스 자체는 로그인·가입이 뭔지
 * 모른다(global에 둔 이유).
 *
 * 만료된 옛 윈도 행은 정리하지 않는다 - 이 앱 규모에서 무한정 커질 걱정은 없고(윈도·버킷
 * 조합마다 한 행), 정리 배치를 새로 만드는 비용이 이득보다 크다고 판단했다.
 */
@Service
class RateLimitService(private val repository: AuthRateLimitRepository) {

    /** bucketKey가 null이면(IP를 특정할 수 없는 등) 아무 것도 하지 않고 통과시킨다. */
    @Transactional(readOnly = true)
    fun checkNotExceeded(bucketKey: String?, limit: Int, window: Duration) {
        if (bucketKey == null) return

        val windowStart = floorToWindow(Instant.now(), window)
        val hitCount = repository.findByBucketKeyAndWindowStart(bucketKey, windowStart)?.hitCount ?: 0
        if (hitCount >= limit) {
            throw RateLimitExceededException("Too many requests, please try again later")
        }
    }

    /** bucketKey가 null이면 아무 것도 하지 않는다. */
    @Transactional
    fun recordHit(bucketKey: String?, window: Duration) {
        if (bucketKey == null) return

        val windowStart = floorToWindow(Instant.now(), window)
        repository.incrementHit(bucketKey, windowStart)
    }

    private fun floorToWindow(now: Instant, window: Duration): Instant {
        val windowSeconds = window.seconds
        return Instant.ofEpochSecond((now.epochSecond / windowSeconds) * windowSeconds)
    }
}
