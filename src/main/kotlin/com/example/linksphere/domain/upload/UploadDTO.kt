package com.example.linksphere.domain.upload

data class UploadUrlRequest(val fileExtension: String)

data class UploadUrlResponse(
    val uploadUrl: String,
    val token: String,
    val publicUrl: String,
)
