package com.example.linksphere.domain.upload

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class UploadController(private val uploadService: UploadService) {

    @PostMapping("/upload/signed-url")
    fun createSignedUploadUrl(
        @RequestBody request: UploadUrlRequest,
        authentication: Authentication,
    ): ApiResponse<UploadUrlResponse> {
        authentication.getUserId() ?: throw IllegalArgumentException("User not authenticated")
        return ApiResponse(
            HttpStatus.CREATED.value(),
            "서명된 업로드 URL 발급 성공",
            uploadService.createSignedUploadUrl(request),
        )
    }
}
