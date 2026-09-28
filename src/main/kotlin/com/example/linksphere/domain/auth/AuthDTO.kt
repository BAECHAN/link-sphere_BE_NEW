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
    // 형식만 검증한다 - 실제 도달 가능한 주소인지는 가입 후 인증메일 확인으로 검증한다
    // (미인증이어도 로그인은 되고, 글쓰기·댓글쓰기만 막힌다 - AuthService.signup 참고)
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

data class AccountResponse(
    val id: String,
    val nickname: String? = null,
    val role: String = "USER", // Default role
    val image: String? = null,
    // 미인증이어도 로그인은 성공하므로 로그인된 사람도 false일 수 있다 - FE가 배지·글쓰기
    // 차단 판단에 쓴다.
    val emailVerified: Boolean = false,
    // FE의 이메일 인증 재발송 버튼이 이 값을 그대로 요청 body에 실어 보낸다(재발송 API는
    // 로그인 여부와 무관하게 이메일만으로 호출하도록 설계돼 세션에서 유추하지 않는다 -
    // AuthController.requestEmailVerification 참고). 이 DTO의 다른 필드처럼 기본값을 둔다.
    val email: String = "",
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
