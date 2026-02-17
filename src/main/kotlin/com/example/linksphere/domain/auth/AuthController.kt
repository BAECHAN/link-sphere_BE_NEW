package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.ApiResponse
import java.security.Principal
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

        @PostMapping("/signup")
        fun signup(
                @RequestBody request: SignupRequest
        ): ResponseEntity<ApiResponse<AccountResponse>> {
                val account = authService.signup(request)
                val response = ApiResponse(HttpStatus.CREATED.value(), "Signup successful", account)
                return ResponseEntity.status(HttpStatus.CREATED).body(response)
        }

        @PostMapping("/login")
        fun login(@RequestBody request: LoginRequest): ResponseEntity<ApiResponse<TokenResponse>> {
                val authResult = authService.login(request)
                val response =
                        ApiResponse(
                                HttpStatus.OK.value(),
                                "Login successful",
                                TokenResponse(authResult.accessToken)
                        )
                return createCookieResponse(authResult, response)
        }

        @PostMapping("/refresh")
        fun refresh(
                @org.springframework.web.bind.annotation.CookieValue("refreshToken")
                refreshToken: String
        ): ResponseEntity<ApiResponse<TokenResponse>> {
                val authResult = authService.refresh(refreshToken)
                val response =
                        ApiResponse(
                                HttpStatus.OK.value(),
                                "Token refreshed",
                                TokenResponse(authResult.accessToken)
                        )
                return ResponseEntity.ok(response)
        }

        @PostMapping("/logout")
        fun logout(): ResponseEntity<ApiResponse<Unit>> {
                val cookie =
                        ResponseCookie.from("refreshToken", "")
                                .httpOnly(true)
                                .secure(true)
                                .path("/")
                                .maxAge(0) // Delete cookie
                                .sameSite("Strict")
                                .build()

                val response = ApiResponse(HttpStatus.OK.value(), "Logout successful", Unit)
                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie.toString())
                        .body(response)
        }

        @GetMapping("/account")
        fun getAccount(principal: Principal): ResponseEntity<ApiResponse<AccountResponse>> {
                val account = authService.getAccount(principal.name)
                val response = ApiResponse(HttpStatus.OK.value(), "Account retrieved", account)
                return ResponseEntity.ok(response)
        }

        private fun createCookieResponse(
                authResult: AuthResult,
                body: ApiResponse<TokenResponse>
        ): ResponseEntity<ApiResponse<TokenResponse>> {
                val cookie =
                        ResponseCookie.from("refreshToken", authResult.refreshToken)
                                .httpOnly(true)
                                .secure(true) // Should be true for https
                                .path("/")
                                .maxAge(7 * 24 * 60 * 60) // 1 week
                                .sameSite("Strict")
                                .build()

                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie.toString())
                        .body(body)
        }
}
