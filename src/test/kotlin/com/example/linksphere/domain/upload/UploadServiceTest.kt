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
    fun `createSignedUploadUrl strips non-alphanumeric characters from the extension`() {
        val signed = SupabaseStorageService.SignedUploadUrl("u", "t", "p")
        `when`(supabaseStorageService.createSignedUploadUrl("png")).thenReturn(signed)

        uploadService.createSignedUploadUrl(UploadUrlRequest("../../png"))

        org.mockito.Mockito.verify(supabaseStorageService).createSignedUploadUrl("png")
    }

    @Test
    fun `createSignedUploadUrl throws when extension has no alphanumeric characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            uploadService.createSignedUploadUrl(UploadUrlRequest("../.."))
        }
        org.mockito.Mockito.verifyNoInteractions(supabaseStorageService)
    }
}
