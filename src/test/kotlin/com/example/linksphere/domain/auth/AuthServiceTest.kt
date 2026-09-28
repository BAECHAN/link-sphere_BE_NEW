package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.global.exception.RateLimitExceededException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Duration
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
    private lateinit var authService: AuthService

    private val emailBucket = "login-fail:${SecureToken.hash("test@example.com")}"
    private val ipBucket = "login-fail:ip:203.0.113.1"

    @BeforeEach
    fun setUp() {
        memberService = mock(MemberService::class.java)
        memberSessionService = mock(MemberSessionService::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        rateLimitService = mock(RateLimitService::class.java)
        authService = AuthService(memberService, memberSessionService, passwordEncoder, rateLimitService)
    }

    @Test
    fun `login은 시도 전에 이메일·IP 두 버킷 모두 한도 확인을 거친다`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(memberService.findByEmail(request.email)).thenThrow(IllegalArgumentException("not found"))

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
        `when`(memberService.findByEmail(request.email)).thenThrow(IllegalArgumentException("not found"))

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
        `when`(memberService.findByEmail(request.email)).thenReturn(member)
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
        `when`(memberService.findByEmail(request.email)).thenThrow(IllegalArgumentException("not found"))

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
        `when`(memberService.findByEmail(request.email)).thenReturn(member)
        `when`(passwordEncoder.matches(request.password, member.password)).thenReturn(true)
        `when`(memberSessionService.createSession(memberId))
            .thenReturn(IssuedSession("access", "refresh", 604800L))

        val result = authService.login(request, "203.0.113.1")

        verify(rateLimitService).checkNotExceeded(emailBucket, 5, Duration.ofMinutes(15))
        verify(rateLimitService).checkNotExceeded(ipBucket, 20, Duration.ofMinutes(15))
        verifyNoMoreInteractions(rateLimitService)
        assertEquals("access", result.accessToken)
    }

    @Test
    fun `signup은 IP 버킷 한도를 확인한 뒤 히트를 기록하고 가입을 진행한다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded")))
            .thenReturn(TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded"))

        authService.signup(request, "203.0.113.1")

        verify(rateLimitService).checkNotExceeded("signup:ip:203.0.113.1", 5, Duration.ofHours(1))
        verify(rateLimitService).recordHit("signup:ip:203.0.113.1", Duration.ofHours(1))
    }

    @Test
    fun `signup은 clientIp가 null이면 IP 버킷을 건드리지 않는다(RateLimitService의 null 무시에 위임)`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(passwordEncoder.encode(request.password)).thenReturn("encoded")
        `when`(memberService.signup(request.copy(password = "encoded")))
            .thenReturn(TableMember(id = UUID.randomUUID(), email = request.email, password = "encoded"))

        authService.signup(request, null)

        verify(rateLimitService).checkNotExceeded(null, 5, Duration.ofHours(1))
        verify(rateLimitService).recordHit(null, Duration.ofHours(1))
    }

    @Test
    fun `signup은 한도 초과 시 회원 가입 자체를 하지 않는다`() {
        val request = SignupRequest("new@example.com", "password1!", "newuser")
        `when`(rateLimitService.checkNotExceeded("signup:ip:203.0.113.1", 5, Duration.ofHours(1)))
            .thenThrow(RateLimitExceededException("Too many requests, please try again later"))

        assertThrows(RateLimitExceededException::class.java) {
            authService.signup(request, "203.0.113.1")
        }

        verifyNoInteractions(memberService)
    }
}
