package com.example.linksphere.domain.auth

data class LoginRequest(
        val email: String,
        val password: String // Password is now required
)

data class SignupRequest(val email: String, val password: String, val name: String? = null)

data class TokenResponse(val accessToken: String)

data class AuthResult(val accessToken: String, val refreshToken: String)
