package com.example.linksphere.domain.auth

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.validation.annotation.Validated
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
@Validated // @RequestParam(개별 메서드 파라미터)에 붙인 @Size가 동작하려면 클래스 레벨에 필요하다
class AuthController(private val authService: AuthService) {

    companion object {
        // __Host- 접두어는 브라우저가 Secure·Path=/·Domain 속성 없음을 강제하는 쿠키 이름
        // 규약이다(둘 다 이미 만족). Phase 3에서 세션을 DB 기반으로 바꾸며 함께 붙였다 -
        // 기존 refreshToken 쿠키는 새 세션 테이블에 없는 값이 되어 그냥 만료될 때까지
        // 무시된다(전원 재로그인, docs/plans/2026-09-28-auth-hardening.md 참고).
        private const val REFRESH_COOKIE_NAME = "__Host-refreshToken"
    }

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
        description = "accessToken 은 본문으로, refreshToken 은 HttpOnly·Secure·SameSite=Lax 쿠키(최대 7일)로 내려간다. " +
            "둘 다 서버가 발급한 불투명 토큰이다(JWT 아님) - member_sessions 테이블로 매 요청마다 진위를 판정한다. " +
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
        description = "요청 본문이 아니라 refreshToken 쿠키를 읽는다. 성공 시 쿠키도 새로 발급된다(회전 - 이전 " +
            "refresh는 그 순간 폐기되고 재사용하면 같은 로그인에서 나온 세션 전체가 폐기된다). " +
            "실패: 401 MISSING_REFRESH_TOKEN(쿠키 없음) · 401 INVALID_REFRESH_TOKEN(만료·위조·이미 소비됨)",
    )
    @SecurityRequirements
    @PostMapping("/refresh")
    fun refresh(@CookieValue(REFRESH_COOKIE_NAME) refreshToken: String): ResponseEntity<ApiResponse<TokenResponse>> {
        val authResult = authService.refresh(refreshToken)
        return createCookieResponse(
            authResult,
            ApiResponse(HttpStatus.OK.value(), "Token refreshed", TokenResponse(authResult.accessToken)),
        )
    }

    @Operation(
        summary = "로그아웃 (이 기기만)",
        description = "refreshToken 쿠키를 maxAge=0 으로 덮어써 만료시키고, 그 쿠키가 가리키던 세션도 " +
            "서버에서 즉시 폐기한다(다른 기기의 세션은 그대로 유지). 쿠키가 없거나 이미 무효해도 200을 반환한다.",
    )
    @SecurityRequirements
    @PostMapping("/logout")
    fun logout(
        @CookieValue(name = REFRESH_COOKIE_NAME, required = false) refreshToken: String?,
    ): ResponseEntity<ApiResponse<Unit>> {
        authService.logout(refreshToken)
        val cookie =
            ResponseCookie.from(REFRESH_COOKIE_NAME, "")
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
        summary = "로그아웃 (전체 기기)",
        description = "이 계정의 모든 세션을 서버에서 즉시 폐기한다(다른 브라우저·기기에 남아있던 로그인 " +
            "포함). 이 요청을 보낸 기기의 쿠키도 함께 만료시킨다.",
    )
    @PostMapping("/logout-all")
    fun logoutAll(principal: Principal): ResponseEntity<ApiResponse<Unit>> {
        authService.logoutAll(principal.name)
        val cookie =
            ResponseCookie.from(REFRESH_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(true)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build()
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(ApiResponse(HttpStatus.OK.value(), "Logged out of all devices", Unit))
    }

    @Operation(
        summary = "내 계정 조회",
        description = "인증된 세션이 가리키는 회원 ID로 조회한다. 실패: 404 NOT_FOUND(탈퇴 등으로 회원이 없을 때)",
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
        @Valid @RequestBody request: UpdateAccountRequest,
        principal: Principal,
    ): ResponseEntity<ApiResponse<AccountResponse>> = ResponseEntity.ok(ApiResponse(HttpStatus.OK.value(), "Account updated", authService.updateAccount(principal.name, request)))

    // 마이페이지(로그인)와 가입 화면(비로그인) 둘 다에서 쓴다 - permitAll 경로라 인증 안 된
    // 요청은 authentication이 null이 아니라 이름이 "anonymousUser"인 익명 토큰으로 들어오고,
    // getUserId()가 UUID 파싱에 실패해 null을 반환한다(AuthService가 그 null을 "본인 제외 없이
    // 순수 존재 여부만 확인"으로 처리)
    @Operation(
        summary = "닉네임 사용 가능 여부 확인",
        description = "비로그인도 호출할 수 있다. 로그인 상태로 보내면 본인 닉네임은 사용 가능으로 판정한다.",
    )
    @SecurityRequirements
    @GetMapping("/account/nickname-availability")
    fun checkNicknameAvailability(
        @RequestParam @Size(max = 20) nickname: String,
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
        @RequestParam @Size(max = 254) email: String,
    ): ResponseEntity<ApiResponse<EmailAvailabilityResponse>> = ResponseEntity.ok(
        ApiResponse(HttpStatus.OK.value(), "Email availability checked", authService.isEmailAvailable(email)),
    )

    private fun createCookieResponse(
        authResult: AuthResult,
        body: ApiResponse<TokenResponse>,
    ): ResponseEntity<ApiResponse<TokenResponse>> {
        val cookie =
            ResponseCookie.from(REFRESH_COOKIE_NAME, authResult.refreshToken)
                .httpOnly(true)
                .secure(true) // Should be true for https
                .path("/")
                .maxAge(authResult.refreshExpiresInSeconds)
                .sameSite("Lax")
                .build()

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(body)
    }
}
