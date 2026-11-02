package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRole
import com.example.linksphere.domain.member.UpdateAccountRequest
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.config.SecurityConfig
import com.example.linksphere.global.config.security.CustomAccessDeniedHandler
import com.example.linksphere.global.config.security.CustomAuthenticationEntryPoint
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 계정 설정 4개 엔드포인트의 @PreAuthorize가 실제 SecurityConfig·SessionAuthenticationFilter로 역할 권한을 판정하는지
 * 검증한다(MemberRole.permissions). AuthControllerTest는 보안 체인을 빼고 돌아 메서드 보안을 볼 수 없다.
 * 권한 거부는 GlobalExceptionHandler가 403 ACCESS_DENIED로 바꿔야 한다 - 처리기가 없으면 Exception 전체 처리기가
 * 500으로 내보내 App5xx 알람이 울린다.
 */
@WebMvcTest(controllers = [AuthController::class])
@Import(SecurityConfig::class, CustomAuthenticationEntryPoint::class, CustomAccessDeniedHandler::class)
class AccountPermissionSecurityTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @MockitoBean private lateinit var authService: AuthService

    @MockitoBean private lateinit var accountDeletionService: AccountDeletionService

    @MockitoBean private lateinit var passwordResetService: PasswordResetService

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    private val memberId = UUID.randomUUID()

    @Test
    fun `USER 토큰이면 계정 설정 4개 엔드포인트를 모두 통과한다`() {
        tokenFor(MemberRole.USER)
        `when`(authService.updateAccount(memberId, UpdateAccountRequest(nickname = "newNick", image = null)))
            .thenReturn(AccountResponse(id = memberId.toString(), nickname = "newNick"))
        `when`(authService.changePassword(memberId, ChangePasswordRequest(currentPassword = "current1!", newPassword = "newPassword1!")))
            .thenReturn(AuthResult("new-access-token", "new-refresh-token", refreshExpiresInSeconds = 604800L))

        accountRequests().forEach { request ->
            mockMvc.perform(request.header("X-Access-Token", "token")).andExpect(status().isOk)
        }

        verify(authService).logoutAll(memberId)
        verify(accountDeletionService).requestDeletion(memberId, "correct1!")
    }

    @Test
    fun `DEMO 토큰이면 계정 설정 4개 엔드포인트가 서비스에 닿기 전에 403 ACCESS_DENIED`() {
        tokenFor(MemberRole.DEMO)

        accountRequests().forEach { request ->
            mockMvc.perform(request.header("X-Access-Token", "token"))
                .andExpect(status().isForbidden)
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"))
        }

        verifyNoInteractions(authService, accountDeletionService)
    }

    private fun tokenFor(role: MemberRole) {
        `when`(memberSessionService.checkAccessToken("token")).thenReturn(AccessTokenCheck.Valid(memberId, UUID.randomUUID(), role))
    }

    private fun accountRequests(): List<MockHttpServletRequestBuilder> = listOf(
        post("/auth/logout-all"),
        patch("/auth/account").contentType(MediaType.APPLICATION_JSON).content("""{"nickname":"newNick","image":null}"""),
        patch("/auth/account/password").contentType(MediaType.APPLICATION_JSON)
            .content("""{"currentPassword":"current1!","newPassword":"newPassword1!"}"""),
        post("/auth/account/deletion").contentType(MediaType.APPLICATION_JSON).content("""{"password":"correct1!"}"""),
    )
}
