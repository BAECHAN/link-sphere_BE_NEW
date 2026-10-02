package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidActionTokenException
import com.example.linksphere.global.exception.RateLimitExceededException
import com.example.linksphere.infra.mail.MailService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID

// bucketKey·limit·window는 리터럴로 검증한다(any()/eq() 없이) - AuthServiceTest·
// AuthRateLimitRepository.kt와 같은 이유.
class PasswordResetServiceTest {

    private lateinit var memberRepository: MemberRepository
    private lateinit var memberActionTokenRepository: MemberActionTokenRepository
    private lateinit var memberSessionService: MemberSessionService
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var mailService: MailService
    private lateinit var rateLimitService: RateLimitService
    private lateinit var service: PasswordResetService

    private val emailBucket = "password-reset:${SecureToken.hash("test@example.com")}"
    private val ipBucket = "password-reset:ip:203.0.113.1"

    @BeforeEach
    fun setUp() {
        memberRepository = mock(MemberRepository::class.java)
        memberActionTokenRepository = mock(MemberActionTokenRepository::class.java)
        memberSessionService = mock(MemberSessionService::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        mailService = mock(MailService::class.java)
        rateLimitService = mock(RateLimitService::class.java)
        service =
            PasswordResetService(
                memberRepository,
                memberActionTokenRepository,
                memberSessionService,
                passwordEncoder,
                mailService,
                rateLimitService,
                "https://test.example",
            )
    }

    @Test
    fun `requestReset은 이메일·IP 두 버킷 모두 한도를 소비(기록+확인)한다`() {
        service.requestReset("test@example.com", "203.0.113.1")

        verify(rateLimitService).consume(emailBucket, 3, Duration.ofHours(1))
        verify(rateLimitService).consume(ipBucket, 10, Duration.ofHours(1))
    }

    @Test
    fun `requestReset은 한도 초과 시 회원 조회 자체를 하지 않는다`() {
        `when`(rateLimitService.consume(emailBucket, 3, Duration.ofHours(1)))
            .thenThrow(RateLimitExceededException("Too many requests, please try again later"))

        assertThrows(RateLimitExceededException::class.java) {
            service.requestReset("test@example.com", "203.0.113.1")
        }

        verifyNoInteractions(memberRepository)
    }

    @Test
    fun `requestReset은 존재하지 않는 이메일이어도 조용히 끝난다(메일 발송 없음)`() {
        `when`(memberRepository.findByEmail("test@example.com")).thenReturn(null)

        service.requestReset("test@example.com", "203.0.113.1")

        verifyNoInteractions(mailService, memberActionTokenRepository)
    }

    @Test
    fun `requestReset은 존재하는 이메일이면 토큰을 저장하고 메일을 보낸다`() {
        val member = TableMember(id = UUID.randomUUID(), email = "test@example.com", password = "enc", nickname = "tester")
        `when`(memberRepository.findByEmail("test@example.com")).thenReturn(member)

        service.requestReset("test@example.com", "203.0.113.1")

        verify(memberActionTokenRepository).save(any())
        verify(mailService).send(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
        )
    }

    @Test
    fun `confirmReset은 유효하지 않은 토큰이면 InvalidActionTokenException을 던진다`() {
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("bad-token"))).thenReturn(null)

        assertThrows(InvalidActionTokenException::class.java) {
            service.confirmReset("bad-token", "newPassword1!")
        }
    }

    @Test
    fun `confirmReset은 목적이 다른 토큰이면 InvalidActionTokenException을 던진다`() {
        val memberId = UUID.randomUUID()
        val token =
            TableMemberActionToken(
                memberId = memberId,
                purpose = MemberActionTokenPurpose.EMAIL_VERIFY,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)

        assertThrows(InvalidActionTokenException::class.java) {
            service.confirmReset("raw-token", "newPassword1!")
        }

        verify(memberActionTokenRepository, never()).consumeIfActive(any(), any())
    }

    @Test
    fun `confirmReset은 이미 소비됐거나 만료된 토큰이면 InvalidActionTokenException을 던지고 비밀번호를 바꾸지 않는다`() {
        val memberId = UUID.randomUUID()
        val token =
            TableMemberActionToken(
                memberId = memberId,
                purpose = MemberActionTokenPurpose.PASSWORD_RESET,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)
        `when`(memberActionTokenRepository.consumeIfActive(any(), any())).thenReturn(0)

        assertThrows(InvalidActionTokenException::class.java) {
            service.confirmReset("raw-token", "newPassword1!")
        }

        verifyNoInteractions(memberSessionService)
    }

    @Test
    fun `confirmReset은 성공하면 비밀번호를 바꾸고 그 회원의 모든 세션을 폐기한다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "oldEncoded", nickname = "tester")
        val token =
            TableMemberActionToken(
                memberId = memberId,
                purpose = MemberActionTokenPurpose.PASSWORD_RESET,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)
        `when`(memberActionTokenRepository.consumeIfActive(any(), any())).thenReturn(1)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.encode("newPassword1!")).thenReturn("newEncoded")
        `when`(memberRepository.save(member)).thenReturn(member)

        service.confirmReset("raw-token", "newPassword1!")

        org.junit.jupiter.api.Assertions.assertEquals("newEncoded", member.password)
        verify(memberSessionService).revokeAllForMember(memberId)
    }

    @Test
    fun `confirmReset은 이미 익명화(퍼지)된 회원이면 InvalidActionTokenException을 던지고 비밀번호를 바꾸지 않는다`() {
        val memberId = UUID.randomUUID()
        val purgedMember =
            TableMember(
                id = memberId,
                email = "deleted-$memberId@deleted.invalid",
                password = "unmatchable",
                nickname = null,
                deletedAt = Instant.now(),
            )
        val token =
            TableMemberActionToken(
                memberId = memberId,
                purpose = MemberActionTokenPurpose.PASSWORD_RESET,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)
        `when`(memberActionTokenRepository.consumeIfActive(any(), any())).thenReturn(1)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(purgedMember))

        assertThrows(InvalidActionTokenException::class.java) {
            service.confirmReset("raw-token", "newPassword1!")
        }

        verify(memberRepository, never()).save(org.mockito.ArgumentMatchers.any())
        verifyNoInteractions(memberSessionService)
    }

    @Test
    fun `confirmReset은 탈퇴 유예 중(퍼지 전)인 회원이면 정상적으로 비밀번호를 바꾼다`() {
        val memberId = UUID.randomUUID()
        val pendingMember =
            TableMember(
                id = memberId,
                email = "test@example.com",
                password = "oldEncoded",
                nickname = "tester",
                deletionRequestedAt = Instant.now(),
            )
        val token =
            TableMemberActionToken(
                memberId = memberId,
                purpose = MemberActionTokenPurpose.PASSWORD_RESET,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)
        `when`(memberActionTokenRepository.consumeIfActive(any(), any())).thenReturn(1)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(pendingMember))
        `when`(passwordEncoder.encode("newPassword1!")).thenReturn("newEncoded")
        `when`(memberRepository.save(pendingMember)).thenReturn(pendingMember)

        service.confirmReset("raw-token", "newPassword1!")

        org.junit.jupiter.api.Assertions.assertEquals("newEncoded", pendingMember.password)
        verify(memberSessionService).revokeAllForMember(memberId)
    }
}
