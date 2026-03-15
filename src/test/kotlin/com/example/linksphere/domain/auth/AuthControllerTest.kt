package com.example.linksphere.domain.auth

import com.example.linksphere.domain.auth.jwt.JwtTokenProvider
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
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
                                classes = [SecurityFilterChain::class]
                        )]
)
class AuthControllerTest {

        @Autowired private lateinit var mockMvc: MockMvc

        @MockitoBean private lateinit var authService: AuthService

        @MockitoBean private lateinit var memberService: MemberService

        @MockitoBean private lateinit var jwtTokenProvider: JwtTokenProvider

        @WithMockUser
        fun `signup returns 409 when DuplicateMemberException is thrown`() {
                val request = SignupRequest("test@example.com", "password", "testuser")
                `when`(authService.signup(request))
                        .thenThrow(
                                DuplicateMemberException("Email already exists: test@example.com")
                        )

                val mapper = jacksonObjectMapper()
                val json = mapper.writeValueAsString(request)

                mockMvc.perform(
                                post("/auth/signup")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json)
                                        .with(csrf())
                        )
                        .andExpect(status().isConflict)
                        .andExpect(
                                content()
                                        .json(
                                                """{"status":409,"code":"DUPLICATE_MEMBER","message":"Email already exists: test@example.com"}"""
                                        )
                        )
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
                        updatedAt = "2024-01-02T00:00:00"
                )
                `when`(authService.updateAccount("user", request)).thenReturn(response)

                val mapper = jacksonObjectMapper()
                val json = mapper.writeValueAsString(request)

                mockMvc.perform(
                                patch("/auth/account")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json)
                                        .with(csrf())
                        )
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.data.nickname").value("newNick"))
        }

        @Test
        @WithMockUser
        fun `uploadAvatar returns 200 with imageUrl`() {
                val mockFile = MockMultipartFile("file", "avatar.png", "image/png", "fake-image".toByteArray())
                val response = AvatarUploadResponse(imageUrl = "https://supabase.co/avatars/abc.png")
                `when`(authService.uploadAvatar(mockFile)).thenReturn(response)

                mockMvc.perform(
                                multipart("/auth/account/avatar")
                                        .file(mockFile)
                                        .with(csrf())
                        )
                        .andExpect(status().isOk)
                        .andExpect(jsonPath("$.data.imageUrl").value("https://supabase.co/avatars/abc.png"))
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
                                        .with(csrf())
                        )
                        .andExpect(status().isUnauthorized)
                        .andExpect(
                                content()
                                        .json(
                                                """{"status":401,"code":"INVALID_CREDENTIALS","message":"Invalid email or password"}"""
                                        )
                        )
        }
}
