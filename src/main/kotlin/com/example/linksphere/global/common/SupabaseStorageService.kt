package com.example.linksphere.global.common

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.UUID

@Service
class SupabaseStorageService(
    @Value("\${supabase.url}") private val supabaseUrl: String,
    @Value("\${supabase.key}") private val supabaseKey: String,
    @Value("\${supabase.bucket}") private val bucketName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restTemplate = RestTemplate()

    data class SignedUploadUrl(val uploadUrl: String, val token: String, val publicUrl: String)

    private data class SignUploadUrlApiResponse(val url: String, val token: String)

    /**
     * 클라이언트가 이 스토리지로 직접 업로드할 수 있는 서명된 URL을 발급한다.
     * (Signed Upload URL — https://supabase.com/docs/reference/kotlin/v1/storage-from-createsigneduploadurl)
     *
     * 이 서명 발급 자체는 service role 키로 인증하지만, 반환된 uploadUrl/token은 그 자체가
     * 인증 수단이라 클라이언트는 별도 키 없이 이 값만으로 실제 업로드(PUT)를 수행할 수 있다 —
     * service role 키가 클라이언트에 노출되지 않는다.
     */
    fun createSignedUploadUrl(fileExtension: String): SignedUploadUrl {
        val uniqueFileName = "${UUID.randomUUID()}.$fileExtension"
        val signUrl = "$supabaseUrl/storage/v1/object/upload/sign/$bucketName/$uniqueFileName"

        val headers = HttpHeaders()
        headers.set("Authorization", "Bearer $supabaseKey")
        headers.set("apikey", supabaseKey)
        headers.contentType = MediaType.APPLICATION_JSON
        val requestEntity = HttpEntity("{}", headers)

        try {
            val response =
                restTemplate.exchange(
                    signUrl,
                    HttpMethod.POST,
                    requestEntity,
                    SignUploadUrlApiResponse::class.java,
                )
            val body =
                response.body
                    ?: throw RuntimeException("Signed upload URL response body was empty")

            return SignedUploadUrl(
                uploadUrl = "$supabaseUrl/storage/v1${body.url}",
                token = body.token,
                publicUrl = "$supabaseUrl/storage/v1/object/public/$bucketName/$uniqueFileName",
            )
        } catch (e: org.springframework.web.client.HttpStatusCodeException) {
            log.error(
                "HTTP error while creating signed upload URL. Status: {}, Body: {}",
                e.statusCode,
                e.responseBodyAsString,
                e,
            )
            throw RuntimeException("Failed to create signed upload URL")
        } catch (e: Exception) {
            log.error("Error while creating signed upload URL. Message: {}", e.message, e)
            throw RuntimeException("Failed to create signed upload URL: ${e.message}")
        }
    }
}
