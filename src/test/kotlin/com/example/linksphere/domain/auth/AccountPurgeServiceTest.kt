package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.data.domain.PageRequest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// purge(id, cutoff)의 두 파라미터 모두 Kotlin non-null 타입이라, any()로 매칭하면
// Mockito가 내부적으로 넘기는 null이 Kotlin의 파라미터 null 체크에 걸려 NPE가 나고 그
// 예외가 Mockito 매처 스택을 미소비 상태로 남겨 같은 JVM의 다른 테스트까지 깨뜨린다
// (MemberSessionRepository.kt 상단 주석과 동일 메커니즘, 2026-09-29 실측 확인). now를
// 고정해 실제 cutoff 값을 계산할 수 있으므로 모든 스텁·검증을 any() 없이 리터럴로 한다.
@ExtendWith(MockitoExtension::class)
class AccountPurgeServiceTest {

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var accountDeletionService: AccountDeletionService

    @InjectMocks private lateinit var service: AccountPurgeService

    private val now = Instant.parse("2026-10-01T00:00:00Z")
    private val cutoff = now.minus(14, ChronoUnit.DAYS)
    private val pageable = PageRequest.of(0, 200)

    @Test
    fun `대상이 없으면 전부 0으로 요약한다`() {
        `when`(memberRepository.findIdsPendingPurge(cutoff, pageable)).thenReturn(emptyList())

        val result = service.purgeExpired(now)

        assertEquals(AccountPurgeService.PurgeSummary(0, 0, 0), result)
    }

    @Test
    fun `조회한 cutoff는 now에서 GRACE_PERIOD(14일)를 뺀 값이다`() {
        `when`(memberRepository.findIdsPendingPurge(cutoff, pageable)).thenReturn(emptyList())

        service.purgeExpired(now)

        assertEquals(AccountDeletionService.GRACE_PERIOD, java.time.Duration.between(cutoff, now))
    }

    @Test
    fun `purge가 true를 반환한 회원은 purged로, false를 반환한 회원은 skipped로 센다`() {
        val purgedId = UUID.randomUUID()
        val skippedId = UUID.randomUUID()
        `when`(memberRepository.findIdsPendingPurge(cutoff, pageable)).thenReturn(listOf(purgedId, skippedId))
        `when`(accountDeletionService.purge(purgedId, cutoff)).thenReturn(true)
        `when`(accountDeletionService.purge(skippedId, cutoff)).thenReturn(false)

        val result = service.purgeExpired(now)

        assertEquals(AccountPurgeService.PurgeSummary(1, 1, 0), result)
    }

    @Test
    fun `한 회원의 purge가 예외를 던져도 나머지 회원은 계속 처리한다`() {
        val failingId = UUID.randomUUID()
        val succeedingId = UUID.randomUUID()
        `when`(memberRepository.findIdsPendingPurge(cutoff, pageable)).thenReturn(listOf(failingId, succeedingId))
        doThrow(RuntimeException("DB 오류")).`when`(accountDeletionService).purge(failingId, cutoff)
        `when`(accountDeletionService.purge(succeedingId, cutoff)).thenReturn(true)

        val result = service.purgeExpired(now)

        assertEquals(AccountPurgeService.PurgeSummary(1, 0, 1), result)
    }
}
