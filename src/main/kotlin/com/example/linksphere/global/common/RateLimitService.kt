package com.example.linksphere.global.common

import com.example.linksphere.domain.auth.AuthRateLimitRepository
import com.example.linksphere.global.exception.RateLimitExceededException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
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
 *
 * 두 가지 쓰는 법이 있다:
 * - checkNotExceeded + recordHit: "실패만 센다"처럼 확인과 기록 사이에 조건이 끼는 경우(로그인).
 *   확인과 기록이 분리돼 있어 병렬 요청이 한꺼번에 들어오면 한도를 넘겨 통과할 수 있다.
 * - consume / tryConsume: 시도 자체를 세는 경우. 먼저 기록(upsert)하고 같은 트랜잭션에서 다시
 *   읽는다 - Postgres가 upsert한 행을 커밋까지 잠가 병렬 요청이 차례로 줄을 서므로 위 경쟁이 없다.
 *
 * 기록하는 메서드(recordHit·consume·tryConsume)는 모두 REQUIRES_NEW다 - 호출부는 기록 직후
 * 예외를 던지는 경우가 많다(로그인 실패 → InvalidCredentialsException, 가입 중복 →
 * DuplicateMemberException). 호출부 트랜잭션에 합류하면 그 롤백에 카운터 기록까지 함께
 * 취소돼, 겉으로는 한도가 있는데 실제로는 한 번도 세지지 않는다(2026-10-03 운영에서 로그인
 * 실패 7회가 모두 401로 통과하는 것으로 확인). MemberSessionRepository.revokeFamily와 같은
 * 이유·같은 해법이다. 별도 트랜잭션이라 upsert 행 잠금도 호출부가 끝날 때까지가 아니라 이
 * 메서드가 끝날 때 풀린다.
 */
@Service
class RateLimitService(private val repository: AuthRateLimitRepository) {

    companion object {
        private const val EXCEEDED_MESSAGE = "Too many requests, please try again later"
    }

    /** bucketKey가 null이면(IP를 특정할 수 없는 등) 아무 것도 하지 않고 통과시킨다. */
    @Transactional(readOnly = true)
    fun checkNotExceeded(bucketKey: String?, limit: Int, window: Duration) {
        if (bucketKey == null) return

        val windowStart = floorToWindow(Instant.now(), window)
        val hitCount = repository.findByBucketKeyAndWindowStart(bucketKey, windowStart)?.hitCount ?: 0
        if (hitCount >= limit) {
            throw RateLimitExceededException(EXCEEDED_MESSAGE, retryAfterSeconds(windowStart, window))
        }
    }

    /** bucketKey가 null이면 아무 것도 하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordHit(bucketKey: String?, window: Duration) {
        if (bucketKey == null) return

        val windowStart = floorToWindow(Instant.now(), window)
        repository.incrementHit(bucketKey, windowStart)
    }

    /** 1회 기록하고, 이번 기록으로 한도를 넘었으면 던진다. bucketKey가 null이면 통과시킨다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun consume(bucketKey: String?, limit: Int, window: Duration) {
        if (bucketKey == null) return

        val windowStart = floorToWindow(Instant.now(), window)
        if (incrementAndGet(bucketKey, windowStart) > limit) {
            throw RateLimitExceededException(EXCEEDED_MESSAGE, retryAfterSeconds(windowStart, window))
        }
    }

    /**
     * consume과 같지만 던지지 않고 한도 안이면 true를 돌려준다 - 초과해도 요청을 막지 않고
     * 비싼 부분만 건너뛰는(강등) 호출부용. bucketKey가 null이면 true.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun tryConsume(bucketKey: String?, limit: Int, window: Duration): Boolean {
        if (bucketKey == null) return true

        val windowStart = floorToWindow(Instant.now(), window)
        return incrementAndGet(bucketKey, windowStart) <= limit
    }

    private fun incrementAndGet(bucketKey: String, windowStart: Instant): Int {
        repository.incrementHit(bucketKey, windowStart)
        return repository.findHitCount(bucketKey, windowStart) ?: 0
    }

    private fun floorToWindow(now: Instant, window: Duration): Instant {
        val windowSeconds = window.seconds
        return Instant.ofEpochSecond((now.epochSecond / windowSeconds) * windowSeconds)
    }

    // 고정 윈도라 이번 윈도가 끝나는 시점이 곧 다시 시도할 수 있는 시점이다.
    private fun retryAfterSeconds(windowStart: Instant, window: Duration): Long {
        val remaining = Duration.between(Instant.now(), windowStart.plus(window))
        return remaining.seconds.coerceAtLeast(1)
    }
}
