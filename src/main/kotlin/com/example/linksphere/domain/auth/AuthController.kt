package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse(HttpStatus.CREATED.value(), "Signup successful", authService.signup(request)))

    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<ApiResponse<TokenResponse>> {
        val authResult = authService.login(request)
        return createCookieResponse(
            authResult,
            ApiResponse(HttpStatus.OK.value(), "Login successful", TokenResponse(authResult.accessToken)),
        )
    }

    @PostMapping("/refresh")
    fun refresh(@CookieValue("refreshToken") refreshToken: String): ResponseEntity<ApiResponse<TokenResponse>> {
        val authResult = authService.refresh(refreshToken)
        return createCookieResponse(
            authResult,
            ApiResponse(HttpStatus.OK.value(), "Token refreshed", TokenResponse(authResult.accessToken)),
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
    fun getAccount(principal: Principal): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account retrieved", authService.getAccount(principal.name)))

    @PatchMapping("/account")
    fun updateAccount(
        @RequestBody request: UpdateAccountRequest,
        principal: Principal,
    ): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account updated", authService.updateAccount(principal.name, request)))

    // 마이페이지(로그인)와 가입 화면(비로그인) 둘 다에서 쓴다 - permitAll 경로라 인증 안 된
    // 요청은 authentication이 null이 아니라 이름이 "anonymousUser"인 익명 토큰으로 들어오고,
    // getUserId()가 UUID 파싱에 실패해 null을 반환한다(AuthService가 그 null을 "본인 제외 없이
    // 순수 존재 여부만 확인"으로 처리)
    @GetMapping("/account/nickname-availability")
    fun checkNicknameAvailability(
        @RequestParam nickname: String,
        authentication: Authentication?,
    ): ResponseEntity<ApiResponse<NicknameAvailabilityResponse>> = ResponseEntity.ok(
        ApiResponse(
            HttpStatus.OK.value(),
            "Nickname availability checked",
            authService.isNicknameAvailable(authentication.getUserId()?.toString(), nickname),
        ),
    )

    @GetMapping("/email-availability")
    fun checkEmailAvailability(
        @RequestParam email: String,
    ): ResponseEntity<ApiResponse<EmailAvailabilityResponse>> = ResponseEntity.ok(
        ApiResponse(HttpStatus.OK.value(), "Email availability checked", authService.isEmailAvailable(email)),
    )

    private fun createCookieResponse(
        authResult: AuthResult,
        body: ApiResponse<TokenResponse>,
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
