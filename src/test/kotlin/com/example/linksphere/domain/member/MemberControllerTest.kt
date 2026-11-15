package com.example.linksphere.domain.member

import com.example.linksphere.domain.auth.MemberSessionService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.hamcrest.Matchers.startsWith
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

// AuthControllerTest에 있던 계정 조회·수정·닉네임 확인 테스트를 컨트롤러와 함께 옮겼다(경로는 그대로 /auth/account…).
@WebMvcTest(
    controllers = [MemberController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class MemberControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var memberService: MemberService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `getAccount는 인증된 회원 id로 조회한 계정을 200으로 돌려준다`() {
        `when`(memberService.getAccount(UUID.fromString(MEMBER_ID)))
            .thenReturn(AccountResponse(id = MEMBER_ID, nickname = "nick", email = "a@example.com"))

        mockMvc.perform(get("/auth/account"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.id").value(MEMBER_ID))
            .andExpect(jsonPath("$.data.email").value("a@example.com"))
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
        `when`(memberService.updateAccountAndGet(UUID.fromString(MEMBER_ID), request)).thenReturn(response)

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
    fun `checkNicknameAvailability calls memberService with null userId when unauthenticated`() {
        `when`(memberService.checkNicknameAvailability(null, "newNick"))
            .thenReturn(NicknameAvailabilityResponse(true))

        val response = MemberController(memberService).checkNicknameAvailability("newNick", null)

        assertEquals(true, response.data.available)
    }

    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `checkNicknameAvailability passes the authenticated userId when logged in`() {
        // 마이페이지(로그인)에서 호출하는 경로 - 본인 현재 닉네임은 중복에서 제외되어야 한다
        `when`(memberService.checkNicknameAvailability(UUID.fromString(MEMBER_ID), "newNick"))
            .thenReturn(NicknameAvailabilityResponse(true))

        mockMvc.perform(get("/auth/account/nickname-availability").param("nickname", "newNick"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.available").value(true))
    }

    // 클래스의 @Validated가 빠지면 검증은 여전히 돌지만 Spring MVC 내장 검증(HandlerMethodValidationException)으로 바뀌어
    // message 형식이 달라진다. 컨트롤러를 옮겨도 응답이 그대로인지 message 앞부분까지 고정한다.
    @Test
    @WithMockUser(username = MEMBER_ID)
    fun `checkNicknameAvailability는 닉네임이 21자면 400 INVALID_INPUT과 파라미터 경로로 시작하는 message를 준다`() {
        mockMvc.perform(get("/auth/account/nickname-availability").param("nickname", "a".repeat(21)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
            .andExpect(jsonPath("$.message").value(startsWith("checkNicknameAvailability.nickname:")))
    }

    companion object {
        private const val MEMBER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
