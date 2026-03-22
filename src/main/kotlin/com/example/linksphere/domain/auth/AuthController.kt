package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.ApiResponse
import java.security.Principal
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

        @PostMapping("/signup")
        fun signup(@RequestBody request: SignupRequest): ResponseEntity<ApiResponse<AccountResponse>> =
                ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse(HttpStatus.CREATED.value(), "Signup successful", authService.signup(request)))

        @PostMapping("/login")
        fun login(@RequestBody request: LoginRequest): ResponseEntity<ApiResponse<TokenResponse>> {
                val authResult = authService.login(request)
                return createCookieResponse(
                        authResult,
                        ApiResponse(HttpStatus.OK.value(), "Login successful", TokenResponse(authResult.accessToken))
                )
        }

        @PostMapping("/refresh")
        fun refresh(@CookieValue("refreshToken") refreshToken: String): ResponseEntity<ApiResponse<TokenResponse>> {
                val authResult = authService.refresh(refreshToken)
                return createCookieResponse(
                        authResult,
                        ApiResponse(HttpStatus.OK.value(), "Token refreshed", TokenResponse(authResult.accessToken))
                )
        }

        @PostMapping("/logout")
        fun logout(): ResponseEntity<ApiResponse<Unit>> {
                val cookie =
                        ResponseCookie.from("refreshToken", "")
                                .httpOnly(true)
                                .secure(true)
                                .path("/")
                                .maxAge(0)
                                .sameSite("Lax")
                                .build()
                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie.toString())
                        .body(ApiResponse(HttpStatus.OK.value(), "Logout successful", Unit))
        }

        @GetMapping("/account")
        fun getAccount(principal: Principal): ResponseEntity<ApiResponse<AccountResponse>> =
                ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account retrieved", authService.getAccount(principal.name)))

        @PatchMapping("/account")
        fun updateAccount(
                @RequestBody request: UpdateAccountRequest,
                principal: Principal
        ): ResponseEntity<ApiResponse<AccountResponse>> =
                ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account updated", authService.updateAccount(principal.name, request)))

        @PostMapping("/account/avatar", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
        fun uploadAvatar(
                @RequestPart("file") file: MultipartFile
        ): ResponseEntity<ApiResponse<AvatarUploadResponse>> =
                ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Avatar uploaded", authService.uploadAvatar(file)))

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
                                .sameSite("Lax")
                                .build()

                return ResponseEntity.ok()
                        .header(HttpHeaders.SET_COOKIE, cookie.toString())
                        .body(body)
        }
}
