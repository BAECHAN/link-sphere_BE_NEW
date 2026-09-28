package com.example.linksphere.domain.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class LoginRequest(
    val email: String,
    val password: String, // Password is now required
)

data class SignupRequest(
    @field:NotBlank
    @field:Email
    val email: String,
    // 형식만 검증한다 - 실제 도달 가능한 주소인지는 확인하지 않는다(이메일 인증 미도입)
    @field:Size(min = 8, max = 64)
    @field:Pattern(
        regexp = "^(?=.*[a-zA-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9]).*$",
        message = "Password must contain at least one letter, one digit, and one special character",
    )
    // BCrypt는 72바이트를 넘는 입력을 자르고, 유니코드는 정규화 방식에 따라 같은 문자가
    // 다른 바이트로 인코딩될 수 있어 "분명 같은 비밀번호인데 로그인이 안 되는" 버그로
    // 이어질 수 있다 - 출력 가능 ASCII만 허용해 원천 차단한다(Microsoft Entra ID와 동일한
    // 방식, docs/plans/2026-09-28-auth-hardening.md "확정된 결정들" 참고).
    @field:Pattern(
        regexp = "^[\\x20-\\x7E]*$",
        message = "Password must contain only printable ASCII characters",
    )
    val password: String,
    @field:NotBlank
    @field:Size(min = 2, max = 20)
    @field:Pattern(regexp = "^[a-zA-Z0-9가-힣_.-]*$")
    val nickname: String,
)

data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,
    // SignupRequest.password와 같은 규칙(길이·조합·ASCII 전용) - 비밀번호 확인 칸은
    // FE 전용 UX 가드다(SignUpForm의 confirmPassword와 같은 이유로 서버에는 안 보낸다).
    @field:Size(min = 8, max = 64)
    @field:Pattern(
        regexp = "^(?=.*[a-zA-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9]).*$",
        message = "Password must contain at least one letter, one digit, and one special character",
    )
    @field:Pattern(
        regexp = "^[\\x20-\\x7E]*$",
        message = "Password must contain only printable ASCII characters",
    )
    val newPassword: String,
)

data class DeleteAccountRequest(
    @field:NotBlank
    val password: String,
)

data class TokenResponse(val accessToken: String)

data class AuthResult(val accessToken: String, val refreshToken: String, val refreshExpiresInSeconds: Long)

data class AccountResponse(
    val id: String,
    val nickname: String? = null,
    val role: String = "USER", // Default role
    val image: String? = null,
)

data class UpdateAccountRequest(
    // null 이면 그 필드는 유지한다(부분 수정) - SignupRequest.nickname과 동일한 형식 규칙이지만
    // 여긴 선택 입력이라 @NotBlank는 붙이지 않는다.
    @field:Size(min = 2, max = 20)
    @field:Pattern(regexp = "^[a-zA-Z0-9가-힣_.-]*$")
    val nickname: String? = null,
    @field:Size(max = 2048)
    val image: String? = null,
)

data class NicknameAvailabilityResponse(val available: Boolean)

data class EmailAvailabilityResponse(val available: Boolean)
