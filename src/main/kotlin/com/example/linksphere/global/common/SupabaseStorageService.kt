package com.example.linksphere.global.common

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile

@Service
class SupabaseStorageService(
        @Value("\${supabase.url}") private val supabaseUrl: String,
        @Value("\${supabase.key}") private val supabaseKey: String,
        @Value("\${supabase.bucket}") private val bucketName: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val restTemplate = RestTemplate()

    fun uploadFile(file: MultipartFile): String = uploadFile(file, bucketName)

    fun uploadFile(file: MultipartFile, bucket: String): String {
        log.info(
                "Starting upload to Supabase Storage: bucket={}, fileName={}",
                bucket,
                file.originalFilename
        )
        val originalFilename = file.originalFilename ?: "unknown.tmp"
        val extension = originalFilename.substringAfterLast('.', "")
        val uniqueFileName = "${UUID.randomUUID()}.${extension}"

        val uploadUrl = "${supabaseUrl}/storage/v1/object/${bucket}/${uniqueFileName}"

        val headers = HttpHeaders()
        headers.set("Authorization", "Bearer ${supabaseKey}")
        headers.set("apikey", supabaseKey)

        // Supabase requires the file's content type accurately or defaults to octet-stream
        val contentType = file.contentType ?: MediaType.APPLICATION_OCTET_STREAM_VALUE
        headers.contentType = MediaType.parseMediaType(contentType)

        // Supabase REST endpoint expects the raw binary in the body for single file upload
        // https://supabase.com/docs/reference/javascript/storage-from-upload
        val resource =
                object : ByteArrayResource(file.bytes) {
                    override fun getFilename(): String = uniqueFileName
                }
        val requestEntity = HttpEntity(resource, headers)

        try {
            val response =
                    restTemplate.exchange(
                            uploadUrl,
                            HttpMethod.POST,
                            requestEntity,
                            String::class.java
                    )
            if (response.statusCode.is2xxSuccessful) {
                val publicUrl =
                        "${supabaseUrl}/storage/v1/object/public/${bucket}/${uniqueFileName}"
                log.info("Successfully uploaded file. Public URL: {}", publicUrl)
                return publicUrl
            } else {
                log.error(
                        "Failed to upload file to Supabase. Status: {}, Body: {}",
                        response.statusCode,
                        response.body
                )
                throw RuntimeException("File upload to Supabase failed")
            }
        } catch (e: org.springframework.web.client.HttpStatusCodeException) {
            log.error(
                    "HTTP error during Supabase upload. Status: {}, Body: {}",
                    e.statusCode,
                    e.responseBodyAsString,
                    e
            )
            throw RuntimeException("Failed to upload file")
        } catch (e: Exception) {
            log.error("Error during Supabase storage upload. Message: {}", e.message, e)
            e.printStackTrace()
            throw RuntimeException("Failed to upload file: ${e.message}")
        }
    }
}
