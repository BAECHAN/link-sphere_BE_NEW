package com.example.linksphere.domain.auth

import java.security.Principal
import org.springframework.http.HttpHeaders
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
        fun signup(@RequestBody request: SignupRequest): ResponseEntity<AccountResponse> {
                val account = authService.signup(request)
                return ResponseEntity.status(org.springframework.http.HttpStatus.CREATED)
                        .body(account)
        }

        @PostMapping("/login")
        fun login(@RequestBody request: LoginRequest): ResponseEntity<TokenResponse> {
                val authResult = authService.login(request)
                return createCookieResponse(authResult)
        }

        @PostMapping("/refresh")
        fun refresh(
                @org.springframework.web.bind.annotation.CookieValue("refreshToken")
                refreshToken: String
        ): ResponseEntity<TokenResponse> {
                val authResult = authService.refresh(refreshToken)
                // If we were rotating, we would use createCookieResponse.
                // Since we are just issuing a new access token, we can just return it.
                // However, updating the cookie's path/secure flags or just keeping it alive might
                // be good.
                // But the requirement says "update Access Token".
                // Let's just return the new access token in the body. The old refresh token cookie
                // remains valid until it expires.
                return ResponseEntity.ok(TokenResponse(authResult.accessToken))
        }

        @PostMapping("/logout")
        fun logout(): ResponseEntity<Void> {
                val cookie =
                        ResponseCookie.from("refreshToken", "")
                                .httpOnly(true)
                                .secure(true)
                                .path("/")
                                .maxAge(0) // Delete cookie
                                .sameSite("Strict")
                                .build()

                return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie.toString()).build()
        }

        @GetMapping("/account")
        fun getAccount(principal: Principal): ResponseEntity<AccountResponse> {
                val account = authService.getAccount(principal.name)
                return ResponseEntity.ok(account)
        }

        private fun createCookieResponse(authResult: AuthResult): ResponseEntity<TokenResponse> {
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
                        .body(TokenResponse(authResult.accessToken))
        }
}
