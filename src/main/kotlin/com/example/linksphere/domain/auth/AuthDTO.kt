package com.example.linksphere.domain.auth

import com.fasterxml.jackson.annotation.JsonProperty

data class LoginRequest(
        val email: String,
        val password: String // Password is now required
)

data class SignupRequest(val email: String, val password: String, val nickname: String? = null)

data class TokenResponse(val accessToken: String)

data class AuthResult(val accessToken: String, val refreshToken: String)

data class AccountResponse(
        val id: String,
        val email: String,
        val nickname: String? = null,
        val role: String = "USER", // Default role
        val image: String? = null,
        @JsonProperty("created_at") val createdAt: String,
        @JsonProperty("updated_at") val updatedAt: String
)
