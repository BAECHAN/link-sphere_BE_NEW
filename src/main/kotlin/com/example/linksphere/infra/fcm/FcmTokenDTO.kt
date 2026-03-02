package com.example.linksphere.infra.fcm

import jakarta.validation.constraints.NotBlank

data class RegisterFcmTokenRequest(
    @field:NotBlank val token: String,
    val platform: String = "WEB"  // WEB, ANDROID, IOS
)

data class DeleteFcmTokenRequest(
    @field:NotBlank val token: String
)
