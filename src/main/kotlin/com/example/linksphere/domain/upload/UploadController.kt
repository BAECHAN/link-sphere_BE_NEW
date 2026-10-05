package com.example.linksphere.domain.upload

import com.example.linksphere.global.common.ApiResponse
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.common.getUserId
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

@Tag(name = "업로드", description = "이미지 업로드용 서명 URL 발급")
@RestController
class UploadController(
    private val uploadService: UploadService,
    private val rateLimitService: RateLimitService,
) {

    companion object {
        // 서명 URL 하나가 Supabase Storage 업로드 1건이다 - 댓글 하나에 이미지가 최대 5장이라
        // 시간당 30회면 이미지 댓글 6개 분량이다. docs/TRAFFIC-MANAGEMENT.md "운영 파라미터" 참고.
        private val SIGNED_URL_MEMBER_WINDOW = Duration.ofHours(1)
        private const val SIGNED_URL_MEMBER_LIMIT = 30
    }

    @Operation(
        summary = "이미지 업로드용 서명 URL 발급",
        description = "Supabase Storage 서명 URL 을 발급한다(실제 업로드는 클라이언트가 이 URL로 직접 한다). " +
            "실패: 400 UNSUPPORTED_IMAGE_TYPE(허용되지 않은 확장자) · 429 RATE_LIMIT_EXCEEDED(회원당 시간당 발급 한도 초과)",
    )
    @PostMapping("/upload/signed-url")
    fun createSignedUploadUrl(
        @RequestBody request: UploadUrlRequest,
        authentication: Authentication,
    ): ApiResponse<UploadUrlResponse> {
        val userId = authentication.getUserId() ?: throw IllegalStateException("User not authenticated")
        rateLimitService.consume("upload:member:$userId", SIGNED_URL_MEMBER_LIMIT, SIGNED_URL_MEMBER_WINDOW)
        return ApiResponse(
            HttpStatus.CREATED.value(),
            "서명된 업로드 URL 발급 성공",
            uploadService.createSignedUploadUrl(request),
        )
    }
}
