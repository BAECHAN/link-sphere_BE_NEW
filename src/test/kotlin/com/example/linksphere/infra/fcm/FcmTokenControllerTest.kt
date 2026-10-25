package com.example.linksphere.infra.fcm

import com.example.linksphere.domain.auth.MemberSessionService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * FCM 토큰 등록·해제가 다른 API와 같은 ApiResponse JSON을 주고, 인증된 회원 id로 서비스에 위임하는지 확인한다 -
 * 예전에는 본문 없는 200을 주고 principal 파싱이 실패하면 401을 직접 반환했다(docs/plans/2026-10-25-be-architecture-audit.md PR4).
 */
@WebMvcTest(
    controllers = [FcmTokenController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class FcmTokenControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var fcmTokenService: FcmTokenService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser(username = USER_ID)
    fun `토큰 등록은 ApiResponse 본문을 주고 인증된 회원 id로 위임한다`() {
        mockMvc.perform(
            post("/fcm/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(RegisterFcmTokenRequest(token = "fcm-token")))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))

        // @WithMockUser에는 SessionAuthenticationFilter가 싣는 세션 계열 id(details)가 없다
        verify(fcmTokenService).registerToken(UUID.fromString(USER_ID), "fcm-token", "WEB", null)
    }

    @Test
    @WithMockUser(username = USER_ID)
    fun `토큰 해제는 ApiResponse 본문을 주고 인증된 회원 id로 위임한다`() {
        mockMvc.perform(
            delete("/fcm/token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jacksonObjectMapper().writeValueAsString(DeleteFcmTokenRequest(token = "fcm-token")))
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value(200))

        verify(fcmTokenService).deleteToken(UUID.fromString(USER_ID), "fcm-token")
    }

    companion object {
        private const val USER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
    }
}
