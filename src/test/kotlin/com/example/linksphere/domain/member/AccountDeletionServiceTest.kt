package com.example.linksphere.domain.member

import com.example.linksphere.global.exception.InvalidCredentialsException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

// 다른 패키지의 데이터(세션·알림 토큰·북마크 등)는 이벤트를 듣는 쪽이 지운다 - 여기서는 어떤 이벤트를 언제 내는지만 본다.
// 실제로 다 지워지는지는 AccountPurgeResidueIntegrationTest가 테이블에 남은 행으로 본다.
class AccountDeletionServiceTest {

    private lateinit var memberRepository: MemberRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: AccountDeletionService

    @BeforeEach
    fun setUp() {
        memberRepository = mock(MemberRepository::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        service = AccountDeletionService(memberRepository, passwordEncoder, eventPublisher)
    }

    @Test
    fun `requestDeletion은 비밀번호가 틀리면 아무것도 건드리지 않는다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("wrong", "encoded")).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            service.requestDeletion(memberId, "wrong")
        }

        verify(memberRepository, never()).save(org.mockito.ArgumentMatchers.any())
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `requestDeletion은 성공하면 유예 타임스탬프를 채우고 탈퇴 신청 이벤트만 낸다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester", image = "https://example.com/a.png")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("correct", "encoded")).thenReturn(true)

        service.requestDeletion(memberId, "correct")

        assertEquals("test@example.com", member.email)
        assertEquals("tester", member.nickname)
        assertNotNull(member.deletionRequestedAt)
        assertNull(member.deletedAt)
        verify(memberRepository).save(member)
        verify(eventPublisher).publishEvent(MemberDeletionRequestedEvent(memberId))
        verifyNoMoreInteractions(eventPublisher)
    }

    @Test
    fun `requestDeletion은 이미 유예 중이면 타임스탬프를 유지한다`() {
        val memberId = UUID.randomUUID()
        val original = Instant.now().minus(3, ChronoUnit.DAYS)
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester", deletionRequestedAt = original)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("correct", "encoded")).thenReturn(true)

        service.requestDeletion(memberId, "correct")

        assertEquals(original, member.deletionRequestedAt)
        verify(memberRepository, never()).save(org.mockito.ArgumentMatchers.any())
        verify(eventPublisher).publishEvent(MemberDeletionRequestedEvent(memberId))
    }

    // 세션 폐기나 알림 토큰 삭제가 실패하면 예외가 그대로 올라와야 @Transactional이 유예 시작까지 되돌린다
    @Test
    fun `requestDeletion은 이벤트를 듣는 쪽의 예외를 잡지 않는다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("correct", "encoded")).thenReturn(true)
        doThrow(IllegalStateException("listener failed")).`when`(eventPublisher).publishEvent(MemberDeletionRequestedEvent(memberId))

        assertThrows(IllegalStateException::class.java) { service.requestDeletion(memberId, "correct") }
    }

    @Test
    fun `purge는 claim이 0행이면 아무것도 하지 않고 false를 반환한다`() {
        val memberId = UUID.randomUUID()
        val cutoff = Instant.now().minus(14, ChronoUnit.DAYS)
        `when`(memberRepository.claimForPurge(org.mockito.ArgumentMatchers.eq(memberId), org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any()))
            .thenReturn(0)

        val result = service.purge(memberId, cutoff)

        assertFalse(result)
        verify(memberRepository, never()).findById(memberId)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `purge는 claim이 성공하면 회원 행을 익명화하고 영구 삭제 이벤트만 낸다`() {
        val memberId = UUID.randomUUID()
        val cutoff = Instant.now().minus(14, ChronoUnit.DAYS)
        val member = purgeTarget(memberId)
        `when`(memberRepository.claimForPurge(org.mockito.ArgumentMatchers.eq(memberId), org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any()))
            .thenReturn(1)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.encode(anyString())).thenReturn("unmatchable")

        val result = service.purge(memberId, cutoff)

        assertTrue(result)
        assertEquals("deleted-$memberId@deleted.invalid", member.email)
        assertEquals("unmatchable", member.password)
        assertNull(member.nickname)
        assertNull(member.image)
        assertNotEquals(null, member.deletedAt)
        verify(memberRepository).save(member)
        verify(eventPublisher).publishEvent(MemberPurgedEvent(memberId))
        verifyNoMoreInteractions(eventPublisher)
    }

    // 듣는 쪽 하나가 실패하면 예외가 그대로 올라와야 @Transactional이 그 회원의 삭제 전체를 되돌린다
    @Test
    fun `purge는 이벤트를 듣는 쪽의 예외를 잡지 않는다`() {
        val memberId = UUID.randomUUID()
        val cutoff = Instant.now().minus(14, ChronoUnit.DAYS)
        `when`(memberRepository.claimForPurge(org.mockito.ArgumentMatchers.eq(memberId), org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any()))
            .thenReturn(1)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(purgeTarget(memberId)))
        `when`(passwordEncoder.encode(anyString())).thenReturn("unmatchable")
        doThrow(IllegalStateException("listener failed")).`when`(eventPublisher).publishEvent(MemberPurgedEvent(memberId))

        assertThrows(IllegalStateException::class.java) { service.purge(memberId, cutoff) }
    }

    private fun purgeTarget(memberId: UUID) = TableMember(
        id = memberId,
        email = "test@example.com",
        password = "encoded",
        nickname = "tester",
        image = "https://example.com/a.png",
        deletionRequestedAt = Instant.now().minus(15, ChronoUnit.DAYS),
        deletedAt = Instant.now(),
    )
}
