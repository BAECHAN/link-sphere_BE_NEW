package com.example.linksphere.infra.fcm

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import jakarta.annotation.PostConstruct

@Configuration
class FcmConfig(
    private val resourceLoader: ResourceLoader,
    @Value("\${firebase.service-account-key-path:}") private val keyPath: String,
) {
    private val logger = LoggerFactory.getLogger(FcmConfig::class.java)

    @PostConstruct
    fun initialize() {
        if (FirebaseApp.getApps().isNotEmpty()) {
            logger.info("[FCM] Firebase already initialized")
            return
        }

        val credentials = loadFromFile()
        if (credentials == null) {
            logger.warn("[FCM] Firebase credentials unavailable — FCM disabled")
            return
        }

        val options = FirebaseOptions.builder()
            .setCredentials(credentials)
            .build()

        FirebaseApp.initializeApp(options)
        logger.info("[FCM] Firebase initialized successfully")
    }

    private fun loadFromFile(): GoogleCredentials? {
        if (keyPath.isBlank()) return null
        val resource = resourceLoader.getResource(keyPath)
        if (!resource.exists()) {
            logger.warn("[FCM] firebase-service-account.json not found at: $keyPath")
            return null
        }
        logger.info("[FCM] Loaded credentials from file: $keyPath")
        return GoogleCredentials.fromStream(resource.inputStream)
    }
}
