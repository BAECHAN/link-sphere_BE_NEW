package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "인증", description = "회원가입·로그인·토큰 갱신·계정 조회")
@RestController
@RequestMapping("/auth")
class AuthController(private val authService: AuthService) {

    @Operation(
        summary = "회원가입",
        description = "이 API 만 실제 HTTP 201 로 응답한다. 실패: 400 INVALID_INPUT · 409 DUPLICATE_MEMBER · 409 DUPLICATE_NICKNAME",
    )
    @SecurityRequirements
    @PostMapping("/signup")
    fun signup(@Valid @RequestBody request: SignupRequest): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse(HttpStatus.CREATED.value(), "Signup successful", authService.signup(request)))

    @Operation(
        summary = "로그인",
        description = "accessToken 은 본문으로, refreshToken 은 HttpOnly·Secure·SameSite=Lax 쿠키(7일)로 내려간다. " +
            "실패: 401 INVALID_CREDENTIALS",
    )
    @SecurityRequirements
    @PostMapping("/login")
    fun login(@RequestBody request: LoginRequest): ResponseEntity<ApiResponse<TokenResponse>> {
        val authResult = authService.login(request)
        return createCookieResponse(
            authResult,
            ApiResponse(HttpStatus.OK.value(), "Login successful", TokenResponse(authResult.accessToken)),
        )
    }

    @Operation(
        summary = "액세스 토큰 갱신",
        description = "요청 본문이 아니라 refreshToken 쿠키를 읽는다. 성공 시 쿠키도 새로 발급된다. " +
            "실패: 401 MISSING_REFRESH_TOKEN(쿠키 없음) · 401 INVALID_REFRESH_TOKEN(만료·위조)",
    )
    @SecurityRequirements
    @PostMapping("/refresh")
    fun refresh(@CookieValue("refreshToken") refreshToken: String): ResponseEntity<ApiResponse<TokenResponse>> {
        val authResult = authService.refresh(refreshToken)
        return createCookieResponse(
            authResult,
            ApiResponse(HttpStatus.OK.value(), "Token refreshed", TokenResponse(authResult.accessToken)),
        )
    }

    @Operation(
        summary = "로그아웃",
        description = "refreshToken 쿠키를 maxAge=0 으로 덮어써 만료시킨다. 서버에 저장된 토큰을 지우지는 않는다.",
    )
    @SecurityRequirements
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

    @Operation(
        summary = "내 계정 조회",
        description = "JWT 의 subject(UUID)로 조회한다. 실패: 404 NOT_FOUND(탈퇴 등으로 회원이 없을 때)",
    )
    @GetMapping("/account")
    fun getAccount(principal: Principal): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account retrieved", authService.getAccount(principal.name)))

    @Operation(
        summary = "내 계정 수정",
        description = "nickname·image 만 부분 수정한다(null 인 필드는 유지). " +
            "실패: 404 NOT_FOUND · 409 DUPLICATE_MEMBER(닉네임 중복 시에도 이 코드다)",
    )
    @PatchMapping("/account")
    fun updateAccount(
        @RequestBody request: UpdateAccountRequest,
        principal: Principal,
    ): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account updated", authService.updateAccount(principal.name, request)))

    // 마이페이지(로그인)와 가입 화면(비로그인) 둘 다에서 쓴다 - permitAll 경로라 인증 안 된
    // 요청은 authentication이 null이 아니라 이름이 "anonymousUser"인 익명 토큰으로 들어오고,
    // getUserId()가 UUID 파싱에 실패해 null을 반환한다(AuthService가 그 null을 "본인 제외 없이
    // 순수 존재 여부만 확인"으로 처리)
    @Operation(
        summary = "닉네임 사용 가능 여부 확인",
        description = "비로그인도 호출할 수 있다. JWT 를 함께 보내면 본인 닉네임은 사용 가능으로 판정한다.",
    )
    @SecurityRequirements
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

    @Operation(summary = "이메일 사용 가능 여부 확인", description = "가입 화면에서 쓰는 중복 검사. 대소문자를 구분하지 않는다.")
    @SecurityRequirements
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
