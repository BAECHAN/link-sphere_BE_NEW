package com.example.linksphere.domain.auth

import com.fasterxml.jackson.annotation.JsonProperty
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
    @field:Size(min = 8, max = 20)
    @field:Pattern(
        regexp = "^(?=.*[a-zA-Z])(?=.*[0-9])(?=.*[^a-zA-Z0-9]).*$",
        message = "Password must contain at least one letter, one digit, and one special character",
    )
    val password: String,
    @field:NotBlank
    @field:Size(min = 2, max = 20)
    @field:Pattern(regexp = "^[a-zA-Z0-9가-힣_.-]*$")
    val nickname: String,
)

data class TokenResponse(val accessToken: String)

data class AuthResult(val accessToken: String, val refreshToken: String)

data class AccountResponse(
    val id: String,
    val email: String,
    val nickname: String? = null,
    val role: String = "USER", // Default role
    val image: String? = null,
    @JsonProperty("created_at") val createdAt: String,
    @JsonProperty("updated_at") val updatedAt: String,
)

data class UpdateAccountRequest(
    val nickname: String? = null,
    val image: String? = null,
)

data class NicknameAvailabilityResponse(val available: Boolean)

data class EmailAvailabilityResponse(val available: Boolean)
