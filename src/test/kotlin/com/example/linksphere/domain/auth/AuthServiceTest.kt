package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidActionTokenException
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.global.exception.RateLimitExceededException
import com.example.linksphere.infra.mail.MailService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
import java.time.Instant
import java.util.UUID

// bucketKey·limit·window는 전부 AuthService가 실제로 만드는 값을 그대로 리터럴로 넘겨
// 검증한다(any()/eq() 없이) - RateLimitService는 인터페이스가 아니라 클래스지만 Kotlin
// non-null 파라미터에 any()를 쓰면 같은 Mockito/Kotlin NPE가 재현됨을 실측으로 확인했다
// (AuthRateLimitRepository.kt 주석의 문제와 동일 메커니즘). "호출 안 됨"을 확인할 때는
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

    @Test
    fun `login은 시도 전에 이메일·IP 두 버킷 모두 한도 확인을 거친다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(null)

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
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(null)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verify(rateLimitService).recordHit(emailBucket, Duration.ofMinutes(15))
        verify(rateLimitService).recordHit(ipBucket, Duration.ofMinutes(15))
    }

    @Test
    fun `login은 비밀번호가 틀리면 로그인 실패를 기록한다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        val member = TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded")
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.password)).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.login(request, "203.0.113.1")
        }

        verify(rateLimitService).recordHit(emailBucket, Duration.ofMinutes(15))
        verify(rateLimitService).recordHit(ipBucket, Duration.ofMinutes(15))
    }

    @Test
    fun `login은 clientIp가 null이면 IP 버킷을 건드리지 않는다(RateLimitService의 null 무시에 위임)`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(null)

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
        val member = TableMember(id = memberId, email = request.email, password = "encoded")
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.password)).thenReturn(true)
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        val result = authService.login(request, "203.0.113.1")

        verify(rateLimitService).checkNotExceeded(emailBucket, 5, Duration.ofMinutes(15))
        verify(rateLimitService).checkNotExceeded(ipBucket, 20, Duration.ofMinutes(15))
        verifyNoMoreInteractions(rateLimitService)
        assertEquals("access", result.accessToken)
        assertEquals(false, result.deletionCancelled)
    }

    @Test
    fun `login은 유예 중인 회원이 로그인하면 탈퇴 신청을 취소하고 deletionCancelled를 true로 반환한다`() {
        val request = LoginRequest("test@example.com", "password1!")
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = request.email, password = "encoded", deletionRequestedAt = Instant.now())
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.password)).thenReturn(true)
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
        val member = TableMember(id = memberId, email = request.email, password = "encoded", deletionRequestedAt = Instant.now())
        `when`(memberService.findByEmailOrNull(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.password)).thenReturn(true)
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
            .thenReturn(TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded"))

        authService.signup(request, "203.0.113.1")

        verify(rateLimitService).consume("signup:ip:203.0.113.1", 5, Duration.ofHours(1))
    }

    @Test
    fun `signup은 clientIp가 null이면 IP 버킷을 건드리지 않는다(RateLimitService의 null 무시에 위임)`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded")))
            .thenReturn(TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded"))

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
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded")
        val request = ChangePasswordRequest(currentPassword = "wrong", newPassword = "newPassword1!")
        `when`(memberService.findById(memberId)).thenReturn(member)
        `when`(passwordEncoder.matches("wrong", "encoded")).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            authService.changePassword(memberId.toString(), request)
        }

        verifyNoInteractions(memberSessionService)
    }

    @Test
    fun `changePassword는 성공하면 비밀번호를 바꾸고 모든 세션을 폐기한 뒤 이 기기에 새 세션을 발급한다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "oldEncoded")
        val request = ChangePasswordRequest(currentPassword = "current", newPassword = "newPassword1!")
        `when`(memberService.findById(memberId)).thenReturn(member)
        `when`(passwordEncoder.matches("current", "oldEncoded")).thenReturn(true)
        `when`(passwordEncoder.encode("newPassword1!")).thenReturn("newEncoded")
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        val result = authService.changePassword(memberId.toString(), request)

        verify(memberService).changePassword(memberId, "newEncoded")
        verify(memberSessionService).revokeAllForMember(memberId)
        verify(memberSessionService).createSession(memberId)
        assertEquals("access", result.accessToken)
    }

    @Test
    fun `signup은 성공하면 인증메일용 토큰을 저장하고 메일을 보낸다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        val savedMember = TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded"))).thenReturn(savedMember)

        authService.signup(request, "203.0.113.1")

        verify(memberActionTokenRepository).save(any())
        verify(mailService).send(anyString(), anyString(), anyString())
    }

    @Test
    fun `signup은 인증메일 준비가 실패해도 가입 자체는 성공한다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        val savedMember = TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded"))).thenReturn(savedMember)
        `when`(memberActionTokenRepository.save(any())).thenThrow(RuntimeException("DB hiccup"))

        val result = authService.signup(request, "203.0.113.1")

        assertEquals(savedMember.id.toString(), result.id)
    }

    @Test
    fun `requestEmailVerification은 존재하지 않는 이메일이어도 조용히 끝난다`() {
        `when`(memberService.findByEmailOrNull("unknown@example.com")).thenReturn(null)

        authService.requestEmailVerification("unknown@example.com", "203.0.113.1")

        verifyNoInteractions(memberActionTokenRepository, mailService)
    }

    @Test
    fun `requestEmailVerification은 이미 인증된 회원이면 메일을 보내지 않는다`() {
        val member = TableMember(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = true)
        `when`(memberService.findByEmailOrNull("test@example.com")).thenReturn(member)

        authService.requestEmailVerification("test@example.com", "203.0.113.1")

        verifyNoInteractions(memberActionTokenRepository, mailService)
    }

    @Test
    fun `requestEmailVerification은 미인증 회원이면 토큰을 저장하고 메일을 보낸다`() {
        val member = TableMember(id = UUID.randomUUID(), email = "test@example.com", password = "enc", emailVerified = false)
        `when`(memberService.findByEmailOrNull("test@example.com")).thenReturn(member)

        authService.requestEmailVerification("test@example.com", "203.0.113.1")

        verify(memberActionTokenRepository).save(any())
        verify(mailService).send(anyString(), anyString(), anyString())
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
