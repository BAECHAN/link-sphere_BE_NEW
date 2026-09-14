package com.example.linksphere.domain.upload

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Tag(name = "업로드", description = "이미지 업로드용 서명 URL 발급")
@RestController
class UploadController(private val uploadService: UploadService) {

    @Operation(
        summary = "이미지 업로드용 서명 URL 발급",
        description = "Supabase Storage 서명 URL 을 발급한다(실제 업로드는 클라이언트가 이 URL로 직접 한다). " +
            "허용 확장자가 아니면 400 이 아니라 404 NOT_FOUND 로 응답한다(IllegalArgumentException 공통 매핑). " +
            "실패: 404 NOT_FOUND(허용되지 않은 확장자)",
    )
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
