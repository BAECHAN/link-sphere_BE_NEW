package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.AccountResponse
import com.example.linksphere.domain.member.MemberCredentials
import com.example.linksphere.domain.member.MemberRole
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.SignupRequest
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidActionTokenException
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.global.exception.RateLimitExceededException
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.mail.MailService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.contains
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.util.UUID

// bucketKey·limit·window는 전부 AuthService가 실제로 만드는 값을 그대로 리터럴로 넘겨
// 검증한다(any()/eq() 없이) - RateLimitService는 인터페이스가 아니라 클래스지만 Kotlin
// non-null 파라미터에 any()를 쓰면 같은 Mockito/Kotlin NPE가 재현됨을 실측으로 확인했다
// (RateLimitRepository.kt 주석의 문제와 동일 메커니즘). "호출 안 됨"을 확인할 때는
// verifyNoMoreInteractions로 대신한다.
class AuthServiceTest {

    private lateinit var memberService: MemberService
    private lateinit var memberSessionService: MemberSessionService
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var rateLimitService: RateLimitService
    private lateinit var memberActionTokenRepository: MemberActionTokenRepository
    private lateinit var mailService: MailService
    private lateinit var authService: AuthService

    private val emailBucket = "login-fail:${SecureToken.hash("test@example.com")}"
    private val ipBucket = "login-fail:ip:203.0.113.1"
    private val idempotencyKey = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        memberService = mock(MemberService::class.java)
        memberSessionService = mock(MemberSessionService::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        rateLimitService = mock(RateLimitService::class.java)
        memberActionTokenRepository = mock(MemberActionTokenRepository::class.java)
        mailService = mock(MailService::class.java)
        authService =
            AuthService(
                memberService,
                memberSessionService,
                passwordEncoder,
                rateLimitService,
                memberActionTokenRepository,
                mailService,
                "https://test.example",
            )
    }

    private fun credentials(
        id: UUID = UUID.randomUUID(),
        email: String = "test@example.com",
        password: String = "encoded",
        role: MemberRole = MemberRole.USER,
        emailVerified: Boolean = false,
        deletionRequestedAt: Instant? = null,
    ) = MemberCredentials(id, email, password, role, emailVerified, deletionRequestedAt, deletedAt = null)

    @Test
    fun `login은 시도 전에 이메일·IP 두 버킷 모두 한도 확인을 거친다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(null)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verify(rateLimitService).checkNotExceeded(emailBucket, 5, Duration.ofMinutes(15))
        verify(rateLimitService).checkNotExceeded(ipBucket, 20, Duration.ofMinutes(15))
    }

    @Test
    fun `login은 한도 초과 시 회원 조회 자체를 하지 않는다`() {
        val request = LoginRequest("test@example.com", "password1!")
        `when`(rateLimitService.checkNotExceeded(emailBucket, 5, Duration.ofMinutes(15)))
            .thenThrow(RateLimitExceededException("Too many requests, please try again later"))

        assertThrows(RateLimitExceededException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verifyNoInteractions(memberService)
    }

    @Test
    fun `login은 회원이 없으면 로그인 실패를 두 버킷 모두에 기록한다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(null)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verify(rateLimitService).recordHit(emailBucket, Duration.ofMinutes(15))
        verify(rateLimitService).recordHit(ipBucket, Duration.ofMinutes(15))
    }

    @Test
    fun `login은 비밀번호가 틀리면 로그인 실패를 기록한다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        val member = credentials(id = UUID.randomUUID(), email = request.email, password = "encoded")
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.encodedPassword)).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verify(rateLimitService).recordHit(emailBucket, Duration.ofMinutes(15))
        verify(rateLimitService).recordHit(ipBucket, Duration.ofMinutes(15))
    }

    // 비밀번호를 공개한 공유 계정은 이메일 잠금을 걸지 않는다(MemberRole.sharedCredentials) - IP 버킷은 그대로다
    @Test
    fun `login은 데모 계정의 비밀번호가 틀리면 IP 버킷에만 실패를 기록한다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        val member = credentials(id = UUID.randomUUID(), email = request.email, password = "encoded", role = MemberRole.DEMO)
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.encodedPassword)).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verify(rateLimitService).recordHit(ipBucket, Duration.ofMinutes(15))
        verify(rateLimitService, never()).recordHit(emailBucket, Duration.ofMinutes(15))
    }

    @Test
    fun `login은 clientIp가 null이면 IP 버킷을 건드리지 않는다(RateLimitService의 null 무시에 위임)`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(null)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, null)
        }

        verify(rateLimitService).checkNotExceeded(null, 20, Duration.ofMinutes(15))
        verify(rateLimitService).recordHit(null, Duration.ofMinutes(15))
    }

    @Test
    fun `login은 성공하면 실패 기록을 남기지 않고 세션을 발급한다`() {
        val request = LoginRequest("test@example.com", "password1!")
        val memberId = UUID.randomUUID()
        val member = credentials(id = memberId, email = request.email, password = "encoded")
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.encodedPassword)).thenReturn(true)
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        val result = authService.login(request, "203.0.113.1")

        verify(rateLimitService).checkNotExceeded(emailBucket, 5, Duration.ofMinutes(15))
        verify(rateLimitService).checkNotExceeded(ipBucket, 20, Duration.ofMinutes(15))
        verifyNoMoreInteractions(rateLimitService)
        assertEquals("access", result.accessToken)
        assertEquals(false, result.deletionCancelled)
    }

    // 분기 순서가 바뀌면 안 된다 - 한도 확인 → 회원 조회 → 비밀번호 대조 → 탈퇴 신청 취소 → 세션 발급. 비밀번호보다 유예 확인이
    // 앞서면 "이 이메일이 탈퇴 유예 중"이라는 사실이 비밀번호 없이 드러난다
    @Test
    fun `login은 한도 확인, 회원 조회, 비밀번호 대조, 탈퇴 신청 취소, 세션 발급 순으로 진행한다`() {
        val request = LoginRequest("test@example.com", "password1!")
        val memberId = UUID.randomUUID()
        val member = credentials(id = memberId, email = request.email, password = "encoded")
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.encodedPassword)).thenReturn(true)
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        authService.login(request, "203.0.113.1")

        val order = inOrder(rateLimitService, memberService, passwordEncoder, memberSessionService)
        order.verify(rateLimitService).checkNotExceeded(emailBucket, 5, Duration.ofMinutes(15))
        order.verify(rateLimitService).checkNotExceeded(ipBucket, 20, Duration.ofMinutes(15))
        order.verify(memberService).findCredentialsByEmailOrNull(request.email)
        order.verify(passwordEncoder).matches(request.password, "encoded")
        order.verify(memberService).cancelPendingDeletion(memberId)
        order.verify(memberSessionService).createSession(memberId)
    }

    @Test
    fun `login은 유예 중인 회원이 로그인하면 탈퇴 신청을 취소하고 deletionCancelled를 true로 반환한다`() {
        val request = LoginRequest("test@example.com", "password1!")
        val memberId = UUID.randomUUID()
        val member = credentials(id = memberId, email = request.email, password = "encoded", deletionRequestedAt = Instant.now())
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.encodedPassword)).thenReturn(true)
        `when`(memberService.cancelPendingDeletion(memberId)).thenReturn(true)
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        val result = authService.login(request, "203.0.113.1")

        verify(memberService).cancelPendingDeletion(memberId)
        assertEquals(true, result.deletionCancelled)
    }

    @Test
    fun `login은 퍼지가 먼저 가져간 회원이면(cancelPendingDeletion이 false) 실패 기록 없이 로그인 실패로 처리한다`() {
        val request = LoginRequest("test@example.com", "password1!")
        val memberId = UUID.randomUUID()
        val member = credentials(id = memberId, email = request.email, password = "encoded", deletionRequestedAt = Instant.now())
        `when`(memberService.findCredentialsByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.encodedPassword)).thenReturn(true)
        `when`(memberService.cancelPendingDeletion(memberId)).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verifyNoInteractions(memberSessionService)
        verify(rateLimitService, never()).recordHit(emailBucket, Duration.ofMinutes(15))
    }

    @Test
    fun `signup은 IP 버킷 한도를 소비(기록+확인)한 뒤 가입을 진행한다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded")))
            .thenReturn(AccountResponse(id = UUID.randomUUID().toString(), email = request.email))

        authService.signup(request, "203.0.113.1")

        verify(rateLimitService).consume("signup:ip:203.0.113.1", 5, Duration.ofHours(1))
    }

    @Test
    fun `signup은 clientIp가 null이면 IP 버킷을 건드리지 않는다(RateLimitService의 null 무시에 위임)`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded")))
            .thenReturn(AccountResponse(id = UUID.randomUUID().toString(), email = request.email))

        authService.signup(request, null)

        verify(rateLimitService).consume(null, 5, Duration.ofHours(1))
    }

    @Test
    fun `signup은 한도 초과 시 회원 가입 자체를 하지 않는다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(rateLimitService.consume("signup:ip:203.0.113.1", 5, Duration.ofHours(1)))
            .thenThrow(RateLimitExceededException("Too many requests, please try again later"))

        assertThrows(RateLimitExceededException::class.java) {
            authService.signup(request, "203.0.113.1")
        }

        verifyNoInteractions(memberService)
    }

    @Test
    fun `changePassword는 현재 비밀번호가 틀리면 아무것도 바꾸지 않는다`() {
        val memberId = UUID.randomUUID()
        val member = credentials(id = memberId, email = "test@example.com", password = "encoded")
        val request = ChangePasswordRequest(currentPassword = "wrong", newPassword = "newPassword1!")
        `when`(memberService.getCredentials(memberId)).thenReturn(member)
        `when`(passwordEncoder.matches("wrong", "encoded")).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.changePassword(memberId, request)
        }

        verifyNoInteractions(memberSessionService)
    }

    @Test
    fun `changePassword는 성공하면 비밀번호를 바꾸고 모든 세션을 폐기한 뒤 이 기기에 새 세션을 발급한다`() {
        val memberId = UUID.randomUUID()
        val member = credentials(id = memberId, email = "test@example.com", password = "oldEncoded")
        val request = ChangePasswordRequest(currentPassword = "current", newPassword = "newPassword1!")
        `when`(memberService.getCredentials(memberId)).thenReturn(member)
        `when`(passwordEncoder.matches("current", "oldEncoded")).thenReturn(true)
        `when`(passwordEncoder.encode("newPassword1!")).thenReturn("newEncoded")
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        val result = authService.changePassword(memberId, request)

        verify(memberService).changePassword(memberId, "newEncoded")
        verify(memberSessionService).revokeAllForMember(memberId)
        verify(memberSessionService).createSession(memberId)
        assertEquals("access", result.accessToken)
    }

    @Test
    fun `signup은 성공하면 인증메일용 토큰을 저장하고 메일은 커밋 뒤에 보낸다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        val savedMember = AccountResponse(id = UUID.randomUUID().toString(), email = request.email)
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded"))).thenReturn(savedMember)

        afterCommitOf { authService.signup(request, "203.0.113.1") }

        verify(memberActionTokenRepository).save(any())
        // 링크는 FE 라우트(route-paths.ts의 AUTH.VERIFY_EMAIL)와 같아야 한다 - 2026-11-02까지 /auth가 빠져 404였다
        verify(mailService).send(anyString(), anyString(), contains("https://test.example/auth/verify-email?token="))
    }

    @Test
    fun `signup은 인증메일 준비가 실패해도 가입 자체는 성공한다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        val savedMember = AccountResponse(id = UUID.randomUUID().toString(), email = request.email)
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded"))).thenReturn(savedMember)
        `when`(memberActionTokenRepository.save(any())).thenThrow(RuntimeException("DB hiccup"))

        val result = authService.signup(request, "203.0.113.1")

        assertEquals(savedMember.id, result.id)
    }

    @Test
    fun `requestEmailVerification은 존재하지 않는 이메일이어도 조용히 끝난다`() {
        `when`(memberService.findCredentialsByEmailOrNull("unknown@example.com")).thenReturn(null)

        authService.requestEmailVerification("unknown@example.com", "203.0.113.1", idempotencyKey)

        verifyNoInteractions(memberActionTokenRepository, mailService)
    }

    @Test
    fun `requestEmailVerification은 이미 인증된 회원이면 메일을 보내지 않는다`() {
        val member = credentials(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = true)
        `when`(memberService.findCredentialsByEmailOrNull("test@example.com")).thenReturn(member)

        authService.requestEmailVerification("test@example.com", "203.0.113.1", idempotencyKey)

        verifyNoInteractions(memberActionTokenRepository, mailService)
    }

    @Test
    fun `requestEmailVerification은 미인증 회원이면 토큰을 저장하고 메일을 보낸다`() {
        val member = credentials(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = false)
        `when`(memberService.findCredentialsByEmailOrNull("test@example.com")).thenReturn(member)

        afterCommitOf { authService.requestEmailVerification("test@example.com", "203.0.113.1", idempotencyKey) }

        verify(memberActionTokenRepository).save(any())
        verify(mailService).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `requestEmailVerification은 첫 요청이면 토큰에 키를 저장한다`() {
        val member = credentials(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = false)
        `when`(memberService.findCredentialsByEmailOrNull("test@example.com")).thenReturn(member)

        val captor = ArgumentCaptor.forClass(TableMemberActionToken::class.java)
        afterCommitOf { authService.requestEmailVerification("test@example.com", "203.0.113.1", idempotencyKey) }
        verify(memberActionTokenRepository).save(captor.capture())

        assertEquals(idempotencyKey, captor.value.idempotencyKey)
    }

    @Test
    fun `requestEmailVerification은 같은 키의 토큰이 아직 유효하면 토큰도 메일도 만들지 않는다`() {
        val member = credentials(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = false)
        `when`(memberService.findCredentialsByEmailOrNull("test@example.com")).thenReturn(member)
        `when`(memberActionTokenRepository.findByMemberIdAndIdempotencyKey(member.id, idempotencyKey))
            .thenReturn(verifyTokenOf(member.id, expiresAt = Instant.now().plusSeconds(60)))

        authService.requestEmailVerification("test@example.com", "203.0.113.1", idempotencyKey)

        verify(memberActionTokenRepository, never()).save(any())
        verifyNoInteractions(mailService)
    }

    @Test
    fun `requestEmailVerification은 같은 키의 토큰이 만료됐으면 키 없는 새 토큰과 메일을 만든다`() {
        val member = credentials(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = false)
        `when`(memberService.findCredentialsByEmailOrNull("test@example.com")).thenReturn(member)
        `when`(memberActionTokenRepository.findByMemberIdAndIdempotencyKey(member.id, idempotencyKey))
            .thenReturn(verifyTokenOf(member.id, expiresAt = Instant.now().minusSeconds(60)))

        val captor = ArgumentCaptor.forClass(TableMemberActionToken::class.java)
        afterCommitOf { authService.requestEmailVerification("test@example.com", "203.0.113.1", idempotencyKey) }
        verify(memberActionTokenRepository).save(captor.capture())

        assertNull(captor.value.idempotencyKey)
        verify(mailService).send(anyString(), anyString(), anyString())
    }

    private fun verifyTokenOf(memberId: UUID, expiresAt: Instant) = TableMemberActionToken(
        memberId = memberId,
        purpose = MemberActionTokenPurpose.EMAIL_VERIFY,
        tokenHash = "hash",
        expiresAt = expiresAt,
        idempotencyKey = idempotencyKey,
    )

    // 메일은 토큰 커밋 뒤에만 나가야 한다 - 커밋 전에는 보내지 않았는지 확인한 뒤 afterCommit을 직접 부른다
    // (CommentServiceTest의 이미지 삭제와 같은 방식).
    private fun afterCommitOf(block: () -> Unit) {
        TransactionSynchronizationManager.initSynchronization()
        try {
            block()
            verify(mailService, never()).send(anyString(), anyString(), anyString())
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `confirmEmailVerification은 유효하지 않은 토큰이면 InvalidActionTokenException을 던진다`() {
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("bad-token"))).thenReturn(null)

        assertThrows(InvalidActionTokenException::class.java) {
            authService.confirmEmailVerification("bad-token")
        }
    }

    @Test
    fun `confirmEmailVerification은 목적이 다른 토큰이면 InvalidActionTokenException을 던진다`() {
        val token =
            TableMemberActionToken(
                memberId = UUID.randomUUID(),
                purpose = MemberActionTokenPurpose.PASSWORD_RESET,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)

        assertThrows(InvalidActionTokenException::class.java) {
            authService.confirmEmailVerification("raw-token")
        }

        verify(memberActionTokenRepository, never()).consumeIfActive(any(), any())
    }

    @Test
    fun `confirmEmailVerification은 성공하면 회원을 인증 완료 처리한다`() {
        val memberId = UUID.randomUUID()
        val token =
            TableMemberActionToken(
                memberId = memberId,
                purpose = MemberActionTokenPurpose.EMAIL_VERIFY,
                tokenHash = SecureToken.hash("raw-token"),
                expiresAt = Instant.now().plusSeconds(3600),
            )
        `when`(memberActionTokenRepository.findByTokenHash(SecureToken.hash("raw-token"))).thenReturn(token)
        `when`(memberActionTokenRepository.consumeIfActive(any(), any())).thenReturn(1)

        authService.confirmEmailVerification("raw-token")

        verify(memberService).markEmailVerified(memberId)
    }
}
