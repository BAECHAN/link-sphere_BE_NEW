package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.PasswordPattern
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class LoginRequest(
    val email: String,
    val password: String, // Password is now required
)

data class ChangePasswordRequest(
    @field:NotBlank
    val currentPassword: String,
    // SignupRequest.password와 같은 규칙(길이·조합·ASCII 전용) - 비밀번호 확인 칸은
    // FE 전용 UX 가드다(SignUpForm의 confirmPassword와 같은 이유로 서버에는 안 보낸다).
    @field:Size(min = 8, max = 64)
    @field:Pattern(regexp = PasswordPattern.REGEXP, message = PasswordPattern.MESSAGE)
    val newPassword: String,
)

data class DeleteAccountRequest(
    @field:NotBlank
    val password: String,
)

data class PasswordResetRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)

data class PasswordResetConfirmRequest(
    @field:NotBlank
    val token: String,
    // SignupRequest.password와 같은 규칙(길이·조합·ASCII 전용)
    @field:Size(min = 8, max = 64)
    @field:Pattern(regexp = PasswordPattern.REGEXP, message = PasswordPattern.MESSAGE)
    val newPassword: String,
)

data class EmailVerificationRequest(
    @field:NotBlank
    @field:Email
    val email: String,
)

data class EmailVerificationConfirmRequest(
    @field:NotBlank
    val token: String,
)

// deletionCancelled: 탈퇴 유예 중이던 계정이 이번 로그인으로 복구됐으면 true(로그인에서만
// 의미 있음 - refresh·changePassword 응답은 항상 기본값 false를 그대로 쓴다). FE가 이 값을
// 보고 "탈퇴 신청이 취소됐어요" 안내를 띄운다.
data class TokenResponse(val accessToken: String, val deletionCancelled: Boolean = false)

data class AuthResult(val accessToken: String, val refreshToken: String, val refreshExpiresInSeconds: Long, val deletionCancelled: Boolean = false)

data class EmailAvailabilityResponse(val available: Boolean)
