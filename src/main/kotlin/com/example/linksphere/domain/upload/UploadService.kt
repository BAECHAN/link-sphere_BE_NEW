package com.example.linksphere.domain.upload

import com.example.linksphere.global.common.SupabaseStorageService
import org.springframework.stereotype.Service

@Service
class UploadService(
    private val supabaseStorageService: SupabaseStorageService,
) {
    companion object {
        // FE shared/lib/content/imageContent.ts의 IMAGE_EXT_PATTERN, shared/lib/image/resizeImage.ts가
        // 다루는 svg를 합친 목록 - 두 파일이 실제로 다루는 포맷과 어긋나지 않게 맞췄다.
        private val ALLOWED_EXTENSIONS =
            setOf("jpg", "jpeg", "png", "gif", "webp", "avif", "heic", "heif", "svg")
    }

    fun createSignedUploadUrl(request: UploadUrlRequest): UploadUrlResponse {
        val sanitizedExtension = request.fileExtension.lowercase()
        if (sanitizedExtension !in ALLOWED_EXTENSIONS) {
            throw IllegalArgumentException("Invalid file extension")
        }

        val signed = supabaseStorageService.createSignedUploadUrl(sanitizedExtension)
        return UploadUrlResponse(
            uploadUrl = signed.uploadUrl,
            token = signed.token,
            publicUrl = signed.publicUrl,
        )
    }
}
