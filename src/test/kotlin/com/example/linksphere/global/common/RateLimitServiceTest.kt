package com.example.linksphere.global.common

import com.example.linksphere.domain.auth.AuthRateLimitRepository
import com.example.linksphere.domain.auth.TableAuthRateLimit
import com.example.linksphere.global.exception.RateLimitExceededException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant

// bucketKey·windowStart 둘 다 any()로 매칭한다 - AuthRateLimitRepository.kt 상단 주석 참고
// (Kotlin non-null 파라미터를 eq()로도 감싸면 Mockito/Kotlin NPE가 나고 매처 스택이 오염돼
// 다른 테스트까지 연쇄로 깨짐을 실측 확인해, 두 파라미터 다 nullable로 정정했다).
class RateLimitServiceTest {

    private lateinit var repository: AuthRateLimitRepository
    private lateinit var service: RateLimitService

    @BeforeEach
    fun setUp() {
        repository = mock(AuthRateLimitRepository::class.java)
        service = RateLimitService(repository)
    }

    @Test
    fun `checkNotExceeded는 bucketKey가 null이면 repository를 건드리지 않고 통과시킨다`() {
        service.checkNotExceeded(null, 5, Duration.ofMinutes(15))

        verifyNoInteractions(repository)
    }

    @Test
    fun `checkNotExceeded는 누적 횟수가 한도 미만이면 통과시킨다`() {
        `when`(repository.findByBucketKeyAndWindowStart(any(), any()))
            .thenReturn(TableAuthRateLimit(bucketKey = "bucket", windowStart = Instant.now(), hitCount = 4))

        service.checkNotExceeded("bucket", 5, Duration.ofMinutes(15))
    }

    @Test
    fun `checkNotExceeded는 누적 횟수가 한도에 도달하면 RateLimitExceededException을 던진다`() {
        `when`(repository.findByBucketKeyAndWindowStart(any(), any()))
            .thenReturn(TableAuthRateLimit(bucketKey = "bucket", windowStart = Instant.now(), hitCount = 5))

        assertThrows(RateLimitExceededException::class.java) {
            service.checkNotExceeded("bucket", 5, Duration.ofMinutes(15))
        }
    }

    @Test
    fun `checkNotExceeded는 해당 윈도에 아직 행이 없으면(0회) 통과시킨다`() {
        `when`(repository.findByBucketKeyAndWindowStart(any(), any())).thenReturn(null)

        service.checkNotExceeded("bucket", 5, Duration.ofMinutes(15))
    }

    @Test
    fun `recordHit은 bucketKey가 null이면 repository를 건드리지 않는다`() {
        service.recordHit(null, Duration.ofMinutes(15))

        verifyNoInteractions(repository)
    }

    @Test
    fun `recordHit은 repository의 incrementHit을 호출한다`() {
        service.recordHit("bucket", Duration.ofMinutes(15))

        verify(repository).incrementHit(any(), any())
    }
}
