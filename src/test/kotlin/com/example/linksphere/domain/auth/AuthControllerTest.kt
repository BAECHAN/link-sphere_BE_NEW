package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.SignupRequest
import com.example.linksphere.domain.member.UpdateAccountRequest
import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.global.exception.DuplicateNicknameException
import com.example.linksphere.global.exception.InvalidActionTokenException
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.global.exception.RateLimitExceededException
import com.example.linksphere.global.exception.SessionMismatchException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@WebMvcTest(
    controllers = [AuthController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class AuthControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var authService: AuthService

    @MockitoBean private lateinit var memberService: MemberService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @MockitoBean private lateinit var accountDeletionService: AccountDeletionService

    @MockitoBean private lateinit var passwordResetService: PasswordResetService

    // ResponseEntity.status(CREATED)에서 @ResponseStatus(CREATED)로 바뀌어도 HTTP 상태와 본문 status가 함께 201인지 고정한다
    @Test
    @WithMockUser
    fun `signup 성공은 HTTP 상태와 본문 status가 모두 201이다`() {
        val request = SignupRequest("test@example.com", "password1!", "testuser")
        `when`(authService.signup(request, null)).thenReturn(AccountResponse(id = "some-uuid", nickname = "testuser", image = null))

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value(201))
            .andExpect(jsonPath("$.data.nickname").value("testuser"))
    }

    @Test
    @WithMockUser
    fun `signup returns 409 DUPLICATE_MEMBER when DuplicateMemberException is thrown`() {
        val request = SignupRequest("test@example.com", "password1!", "testuser")
        `when`(authService.signup(request, null))
            .thenThrow(
                DuplicateMemberException("Email already exists"),
            )

        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(request)

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )
            .andExpect(status().isConflict)
            .andExpect(
                content()
                    .json(
                        """{"status":409,"code":"DUPLICATE_MEMBER","message":"Email already exists"}""",
                    ),
            )
    }

    @Test
    @WithMockUser
    fun `signup returns 409 DUPLICATE_NICKNAME when DuplicateNicknameException is thrown`() {
        val request = SignupRequest("test@example.com", "password1!", "testuser")
        `when`(authService.signup(request, null))
            .thenThrow(DuplicateNicknameException("testuser"))

        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(request)

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"))
    }

    @Test
    @WithMockUser
    fun `signup returns 429 RATE_LIMIT_EXCEEDED when RateLimitExceededException is thrown`() {
        val request = SignupRequest("test@example.com", "password1!", "testuser")
        `when`(authService.signup(request, null))
            .thenThrow(RateLimitExceededException("Too many requests, please try again later"))

        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(request)

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
    }

    @Test
    @WithMockUser
    fun `signup returns 400 INVALID_INPUT when email format is invalid`() {
        val request = SignupRequest("not-an-email", "password1!", "testuser")

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `signup returns 400 INVALID_INPUT when password is too short`() {
        val request = SignupRequest("test@example.com", "a1!", "testuser")

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `signup returns 400 INVALID_INPUT when password exceeds 64 characters`() {
        val request = SignupRequest("test@example.com", "a1!".repeat(30), "testuser")

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `signup returns 400 INVALID_INPUT when password contains non-ASCII characters`() {
        val request = SignupRequest("test@example.com", "password1!비밀번호", "testuser")

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `signup returns 400 INVALID_INPUT when nickname is too short`() {
        val request = SignupRequest("test@example.com", "password1!", "a")

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `signup returns 400 INVALID_INPUT when nickname is blank`() {
        val request = SignupRequest("test@example.com", "password1!", "")

        mockMvc.perform(
            post("/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `updateAccount returns 200 with updated AccountResponse`() {
        val request = UpdateAccountRequest(nickname = "newNick", image = null)
        val response = AccountResponse(
            id = "some-uuid",
            nickname = "newNick",
            image = null,
        )
        `when`(authService.updateAccount(UUID.fromString(MEMBER_ID), request)).thenReturn(response)

        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(request)

        mockMvc.perform(
            patch("/auth/account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.nickname").value("newNick"))
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `changePassword 성공 시 __Host-refreshToken 쿠키를 새로 세팅한다`() {
        val request = ChangePasswordRequest(currentPassword = "current1!", newPassword = "newPassword1!")
        `when`(authService.changePassword(UUID.fromString(MEMBER_ID), request))
            .thenReturn(AuthResult("new-access-token", "new-refresh-token", refreshExpiresInSeconds = 604800L))

        mockMvc.perform(
            patch("/auth/account/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(cookie().value("__Host-refreshToken", "new-refresh-token"))
            .andExpect(jsonPath("$.data.accessToken").value("new-access-token"))
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `changePassword returns 401 when current password is wrong`() {
        val request = ChangePasswordRequest(currentPassword = "wrong", newPassword = "newPassword1!")
        `when`(authService.changePassword(UUID.fromString(MEMBER_ID), request))
            .thenThrow(InvalidCredentialsException("Current password is incorrect"))

        mockMvc.perform(
            patch("/auth/account/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `deleteAccount 성공 시 쿠키를 만료시키고 200을 반환한다`() {
        val request = DeleteAccountRequest(password = "correct1!")

        mockMvc.perform(
            delete("/auth/account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(cookie().maxAge("__Host-refreshToken", 0))

        verify(accountDeletionService).requestDeletion(UUID.fromString(MEMBER_ID), "correct1!")
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `deleteAccount returns 401 when password is wrong`() {
        val request = DeleteAccountRequest(password = "wrong")
        doThrow(InvalidCredentialsException("Password is incorrect"))
            .`when`(accountDeletionService).requestDeletion(UUID.fromString(MEMBER_ID), "wrong")

        mockMvc.perform(
            delete("/auth/account")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
    }

    @Test
    @WithMockUser
    fun `requestPasswordReset은 성공하면 200을 반환하고 passwordResetService에 위임한다`() {
        val request = PasswordResetRequest(email = "test@example.com")

        mockMvc.perform(
            post("/auth/password-reset/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)

        verify(passwordResetService).requestReset("test@example.com", null)
    }

    @Test
    @WithMockUser
    fun `confirmPasswordReset은 유효하지 않은 토큰이면 401 INVALID_ACTION_TOKEN을 반환한다`() {
        val request = PasswordResetConfirmRequest(token = "bad-token", newPassword = "newPassword1!")
        doThrow(InvalidActionTokenException("Invalid or expired token"))
            .`when`(passwordResetService).confirmReset("bad-token", "newPassword1!")

        mockMvc.perform(
            post("/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_ACTION_TOKEN"))
    }

    @Test
    @WithMockUser
    fun `confirmPasswordReset은 성공하면 200을 반환한다`() {
        val request = PasswordResetConfirmRequest(token = "good-token", newPassword = "newPassword1!")

        mockMvc.perform(
            post("/auth/password-reset/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)

        verify(passwordResetService).confirmReset("good-token", "newPassword1!")
    }

    @Test
    @WithMockUser
    fun `requestEmailVerification은 성공하면 200을 반환하고 authService에 위임한다`() {
        val request = EmailVerificationRequest(email = "test@example.com")

        mockMvc.perform(
            post("/auth/email-verification/request")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)

        verify(authService).requestEmailVerification("test@example.com", null)
    }

    @Test
    @WithMockUser
    fun `confirmEmailVerification은 유효하지 않은 토큰이면 401 INVALID_ACTION_TOKEN을 반환한다`() {
        val request = EmailVerificationConfirmRequest(token = "bad-token")
        doThrow(InvalidActionTokenException("Invalid or expired token"))
            .`when`(authService).confirmEmailVerification("bad-token")

        mockMvc.perform(
            post("/auth/email-verification/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("INVALID_ACTION_TOKEN"))
    }

    @Test
    @WithMockUser
    fun `confirmEmailVerification은 성공하면 200을 반환한다`() {
        val request = EmailVerificationConfirmRequest(token = "good-token")

        mockMvc.perform(
            post("/auth/email-verification/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)

        verify(authService).confirmEmailVerification("good-token")
    }

    // 이 클래스는 실제 SecurityFilterChain(permitAll 설정)을 제외한 슬라이스 테스트라 MockMvc로는
    // "인증 없이 permitAll 통과" 상태를 재현할 수 없다 - 필터체인이 없으면 스프링부트 기본값이
    // 모든 요청을 거부해 @WithMockUser 없인 401이 난다. 그래서 비로그인 경로는 컨트롤러를 직접
    // 호출해 authentication=null을 넘기는 방식으로 검증한다.
    @Test
    fun `checkNicknameAvailability calls authService with null userId when unauthenticated`() {
        `when`(authService.isNicknameAvailable(null, "newNick"))
            .thenReturn(NicknameAvailabilityResponse(true))

        val response = AuthController(authService, accountDeletionService, passwordResetService).checkNicknameAvailability("newNick", null)

        assertEquals(true, response.data.available)
    }

    @Test
    @WithMockUser
    fun `checkEmailAvailability returns available response`() {
        `when`(authService.isEmailAvailable("new@example.com"))
            .thenReturn(EmailAvailabilityResponse(true))

        mockMvc.perform(get("/auth/email-availability").param("email", "new@example.com"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    // MissingRequestCookieException도 ServletException이지만 전용 핸들러가 이겨 기존 응답(401 MISSING_REFRESH_TOKEN)이 그대로다
    @Test
    @WithMockUser
    fun `refresh 쿠키가 없으면 전용 핸들러가 받아 401 MISSING_REFRESH_TOKEN을 반환한다`() {
        mockMvc.perform(post("/auth/refresh").with(csrf()))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.code").value("MISSING_REFRESH_TOKEN"))
    }

    // 아래 둘은 Spring MVC가 컨트롤러에 넘기기 전에 던지는 표준 예외다 - 예전에는 Exception 안전망이 먼저 잡아 500이었다
    // (운영 재현 2026-10-26, GlobalExceptionHandler.handleSpringMvcException)
    @Test
    @WithMockUser
    fun `필수 파라미터 email이 없으면 500이 아니라 400 INVALID_INPUT을 반환한다`() {
        mockMvc.perform(get("/auth/email-availability"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `JSON이 아닌 Content-Type으로 로그인하면 500이 아니라 415 UNSUPPORTED_MEDIA_TYPE을 반환한다`() {
        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.TEXT_PLAIN)
                .content("email=a@example.com")
                .with(csrf()),
        )
            .andExpect(status().isUnsupportedMediaType)
            .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `checkNicknameAvailability passes the authenticated userId when logged in`() {
        // 마이페이지(로그인)에서 호출하는 경로 - 본인 현재 닉네임은 중복에서 제외되어야 한다
        `when`(authService.isNicknameAvailable(UUID.fromString(MEMBER_ID), "newNick"))
            .thenReturn(NicknameAvailabilityResponse(true))

        mockMvc.perform(get("/auth/account/nickname-availability").param("nickname", "newNick"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    @WithMockUser
    fun `login returns 401 when InvalidCredentialsException is thrown`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(authService.login(request, null))
            .thenThrow(InvalidCredentialsException("Invalid email or password"))

        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(request)

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(
                content()
                    .json(
                        """{"status":401,"code":"INVALID_CREDENTIALS","message":"Invalid email or password"}""",
                    ),
            )
    }

    @Test
    @WithMockUser
    fun `login returns 429 RATE_LIMIT_EXCEEDED when RateLimitExceededException is thrown`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(authService.login(request, null))
            .thenThrow(RateLimitExceededException("Too many requests, please try again later"))

        val mapper = jacksonObjectMapper()
        val json = mapper.writeValueAsString(request)

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"))
    }

    @Test
    @WithMockUser
    fun `login 성공 시 __Host-refreshToken 쿠키를 authResult의 만료 시각만큼 세팅한다`() {
        val request = LoginRequest("test@example.com", "password1!")
        `when`(authService.login(request, null))
            .thenReturn(AuthResult("access-token", "refresh-token", refreshExpiresInSeconds = 604800L))

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(cookie().value("__Host-refreshToken", "refresh-token"))
            .andExpect(cookie().maxAge("__Host-refreshToken", 604800))
            .andExpect(cookie().httpOnly("__Host-refreshToken", true))
            .andExpect(cookie().secure("__Host-refreshToken", true))
            .andExpect(cookie().path("__Host-refreshToken", "/"))
    }

    @Test
    @WithMockUser
    fun `login 응답 본문에 deletionCancelled가 담긴다`() {
        val request = LoginRequest("test@example.com", "password1!")
        `when`(authService.login(request, null))
            .thenReturn(AuthResult("access-token", "refresh-token", refreshExpiresInSeconds = 604800L, deletionCancelled = true))

        mockMvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(request))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.deletionCancelled").value(true))
    }

    @Test
    @WithMockUser
    fun `logout은 쿠키 값을 authService에 그대로 전달하고 쿠키를 만료시킨다`() {
        mockMvc.perform(
            post("/auth/logout")
                .cookie(jakarta.servlet.http.Cookie("__Host-refreshToken", "refresh-token"))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(cookie().maxAge("__Host-refreshToken", 0))

        verify(authService).logout("refresh-token")
    }

    @Test
    @WithMockUser
    fun `logout은 쿠키가 없어도 200을 반환한다`() {
        mockMvc.perform(post("/auth/logout").with(csrf()))
            .andExpect(status().isOk)

        verify(authService).logout(null)
    }

    @Test
    @WithMockUser
    fun `refresh는 X-Access-Token 헤더를 authService에 그대로 넘기고 새 쿠키를 세팅한다`() {
        // 헤더가 있으면 SessionAuthenticationFilter가 먼저 판정한다 - refresh 때 실리는 건 대개 만료된 토큰이다
        `when`(memberSessionService.checkAccessToken("access-a")).thenReturn(AccessTokenCheck.Expired)
        `when`(authService.refresh("refresh-token", "access-a"))
            .thenReturn(AuthResult("access-new", "refresh-new", refreshExpiresInSeconds = 3600L))

        mockMvc.perform(
            post("/auth/refresh")
                .cookie(jakarta.servlet.http.Cookie("__Host-refreshToken", "refresh-token"))
                .header("X-Access-Token", "access-a")
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.accessToken").value("access-new"))
            .andExpect(cookie().value("__Host-refreshToken", "refresh-new"))
    }

    @Test
    @WithMockUser
    fun `refresh는 X-Access-Token 헤더가 없으면 힌트 없이 넘긴다(새로고침 직후)`() {
        `when`(authService.refresh("refresh-token", null))
            .thenReturn(AuthResult("access-new", "refresh-new", refreshExpiresInSeconds = 3600L))

        mockMvc.perform(
            post("/auth/refresh")
                .cookie(jakarta.servlet.http.Cookie("__Host-refreshToken", "refresh-token"))
                .with(csrf()),
        )
            .andExpect(status().isOk)

        verify(authService).refresh("refresh-token", null)
    }

    @Test
    @WithMockUser
    fun `refresh는 SessionMismatchException이면 409 SESSION_MISMATCH를 반환하고 쿠키를 건드리지 않는다`() {
        `when`(memberSessionService.checkAccessToken("access-a")).thenReturn(AccessTokenCheck.Expired)
        `when`(authService.refresh("refresh-token", "access-a")).thenThrow(SessionMismatchException())

        mockMvc.perform(
            post("/auth/refresh")
                .cookie(jakarta.servlet.http.Cookie("__Host-refreshToken", "refresh-token"))
                .header("X-Access-Token", "access-a")
                .with(csrf()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SESSION_MISMATCH"))
            .andExpect(cookie().doesNotExist("__Host-refreshToken"))
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `logout-all은 인증된 사용자 id로 authService를 호출하고 쿠키를 만료시킨다`() {
        mockMvc.perform(post("/auth/logout-all").with(csrf()))
            .andExpect(status().isOk)
            .andExpect(cookie().maxAge("__Host-refreshToken", 0))

        verify(authService).logoutAll(UUID.fromString(MEMBER_ID))
    }

    companion object {
        // 컨트롤러가 getUserId()로 principal 이름을 UUID로 읽으므로 @WithMockUser 기본값("user") 대신 UUID를 쓴다
        private const val MEMBER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
