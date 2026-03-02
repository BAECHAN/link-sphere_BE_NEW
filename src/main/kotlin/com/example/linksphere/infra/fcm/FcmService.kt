package com.example.linksphere.infra.fcm

import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FcmService(
    private val fcmTokenService: FcmTokenService,
    private val fcmTokenRepository: FcmTokenRepository
) {

    private val logger = LoggerFactory.getLogger(FcmService::class.java)

    @Transactional
    fun sendToUser(userId: UUID, title: String, body: String, data: Map<String, String> = emptyMap()) {
        // Firebase가 초기화되지 않았으면 조용히 스킵
        if (FirebaseApp.getApps().isEmpty()) {
            logger.warn("[FCM] Firebase not initialized — skipping notification to userId: $userId")
            return
        }

        val tokens = fcmTokenService.getTokensByUserId(userId)
        if (tokens.isEmpty()) {
            logger.debug("[FCM] No tokens for userId: $userId — skipping")
            return
        }

        val message = MulticastMessage.builder()
            .setNotification(
                Notification.builder()
                    .setTitle(title)
                    .setBody(body)
                    .build()
            )
            .putAllData(data)
            .addAllTokens(tokens)
            .build()

        try {
            val response = FirebaseMessaging.getInstance().sendEachForMulticast(message)
            logger.info("[FCM] Sent to userId: $userId — success: ${response.successCount}, fail: ${response.failureCount}")

            // 만료/무효 토큰 자동 정리
            if (response.failureCount > 0) {
                val invalidTokens = response.responses
                    .zip(tokens)
                    .filter { (resp, _) ->
                        !resp.isSuccessful && isInvalidTokenError(resp.exception)
                    }
                    .map { (_, token) -> token }

                invalidTokens.forEach { token ->
                    logger.info("[FCM] Removing invalid token")
                    fcmTokenRepository.deleteByToken(token)
                }
            }
        } catch (e: FirebaseMessagingException) {
            logger.error("[FCM] Failed to send notification to userId: $userId", e)
        }
    }

    private fun isInvalidTokenError(exception: FirebaseMessagingException?): Boolean {
        if (exception == null) return false
        return exception.messagingErrorCode in listOf(
            MessagingErrorCode.UNREGISTERED,
            MessagingErrorCode.INVALID_ARGUMENT
        )
    }
}
