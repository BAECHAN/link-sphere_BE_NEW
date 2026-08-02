package com.example.linksphere.domain.upload

import com.example.linksphere.global.common.SupabaseStorageService
import org.springframework.stereotype.Service

@Service
class UploadService(
    private val supabaseStorageService: SupabaseStorageService,
) {
    fun createSignedUploadUrl(request: UploadUrlRequest): UploadUrlResponse {
        val sanitizedExtension =
            request.fileExtension.filter { it.isLetterOrDigit() }
                .ifEmpty { throw IllegalArgumentException("Invalid file extension") }

        val signed = supabaseStorageService.createSignedUploadUrl(sanitizedExtension)
        return UploadUrlResponse(
            uploadUrl = signed.uploadUrl,
            token = signed.token,
            publicUrl = signed.publicUrl,
        )
    }
}
