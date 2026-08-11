package com.example.linksphere.domain.auth

import com.example.linksphere.domain.auth.jwt.JwtTokenProvider
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.global.exception.DuplicateNicknameException
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

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

    @MockitoBean private lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    @WithMockUser
    fun `signup returns 409 DUPLICATE_MEMBER when DuplicateMemberException is thrown`() {
        val request = SignupRequest("test@example.com", "password1!", "testuser")
        `when`(authService.signup(request))
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
        `when`(authService.signup(request))
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
    @WithMockUser
    fun `updateAccount returns 200 with updated AccountResponse`() {
        val request = UpdateAccountRequest(nickname = "newNick", image = null)
        val response = AccountResponse(
            id = "some-uuid",
            email = "test@example.com",
            nickname = "newNick",
            image = null,
            createdAt = "2024-01-01T00:00:00",
            updatedAt = "2024-01-02T00:00:00",
        )
        `when`(authService.updateAccount("user", request)).thenReturn(response)

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

    // 이 클래스는 실제 SecurityFilterChain(permitAll 설정)을 제외한 슬라이스 테스트라 MockMvc로는
    // "인증 없이 permitAll 통과" 상태를 재현할 수 없다 - 필터체인이 없으면 스프링부트 기본값이
    // 모든 요청을 거부해 @WithMockUser 없인 401이 난다. 그래서 비로그인 경로는 컨트롤러를 직접
    // 호출해 authentication=null을 넘기는 방식으로 검증한다.
    @Test
    fun `checkNicknameAvailability calls authService with null userId when unauthenticated`() {
        `when`(authService.isNicknameAvailable(null, "newNick"))
            .thenReturn(NicknameAvailabilityResponse(true))

        val response = AuthController(authService).checkNicknameAvailability("newNick", null)

        assertEquals(true, response.body?.data?.available)
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

    @Test
    @WithMockUser(username = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    fun `checkNicknameAvailability passes the authenticated userId when logged in`() {
        // 마이페이지(로그인)에서 호출하는 경로 - 본인 현재 닉네임은 중복에서 제외되어야 한다
        `when`(authService.isNicknameAvailable("3fa85f64-5717-4562-b3fc-2c963f66afa6", "newNick"))
            .thenReturn(NicknameAvailabilityResponse(true))

        mockMvc.perform(get("/auth/account/nickname-availability").param("nickname", "newNick"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    @Test
    @WithMockUser
    fun `login returns 401 when InvalidCredentialsException is thrown`() {
        val request = LoginRequest("test@example.com", "wrongpassword")
        `when`(authService.login(request))
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
}
