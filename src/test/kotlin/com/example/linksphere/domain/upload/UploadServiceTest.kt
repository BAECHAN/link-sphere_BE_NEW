package com.example.linksphere.domain.upload

import com.example.linksphere.global.common.SupabaseStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class UploadServiceTest {

    @Mock private lateinit var supabaseStorageService: SupabaseStorageService

    @InjectMocks private lateinit var uploadService: UploadService

    @Test
    fun `createSignedUploadUrl delegates with the given extension`() {
        val signed = SupabaseStorageService.SignedUploadUrl("https://upload", "token", "https://public")
        `when`(supabaseStorageService.createSignedUploadUrl("png")).thenReturn(signed)

        val result = uploadService.createSignedUploadUrl(UploadUrlRequest("png"))

        assertEquals("https://upload", result.uploadUrl)
        assertEquals("token", result.token)
        assertEquals("https://public", result.publicUrl)
    }

    @Test
    fun `createSignedUploadUrl is case-insensitive against the allowlist`() {
        val signed = SupabaseStorageService.SignedUploadUrl("u", "t", "p")
        `when`(supabaseStorageService.createSignedUploadUrl("png")).thenReturn(signed)

        uploadService.createSignedUploadUrl(UploadUrlRequest("PNG"))

        org.mockito.Mockito.verify(supabaseStorageService).createSignedUploadUrl("png")
    }

    @Test
    fun `createSignedUploadUrl throws when extension has no alphanumeric characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            uploadService.createSignedUploadUrl(UploadUrlRequest("../.."))
        }
        org.mockito.Mockito.verifyNoInteractions(supabaseStorageService)
    }

    @Test
    fun `createSignedUploadUrl rejects path-traversal-like input instead of sanitizing it`() {
        // 예전엔 영숫자만 걸러내 "../../png" 같은 입력도 "png"로 정제해 통과시켰다. 이제는 허용
        // 목록과 정확히 일치해야만 통과한다 - 정제해서 살리는 대신 통째로 거부한다.
        assertThrows(IllegalArgumentException::class.java) {
            uploadService.createSignedUploadUrl(UploadUrlRequest("../../png"))
        }
        org.mockito.Mockito.verifyNoInteractions(supabaseStorageService)
    }

    @Test
    fun `createSignedUploadUrl rejects non-image extensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            uploadService.createSignedUploadUrl(UploadUrlRequest("exe"))
        }
        org.mockito.Mockito.verifyNoInteractions(supabaseStorageService)
    }
}
