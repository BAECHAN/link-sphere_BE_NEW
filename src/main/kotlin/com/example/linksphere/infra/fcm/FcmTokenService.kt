package com.example.linksphere.infra.fcm

import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FcmTokenService(private val fcmTokenRepository: FcmTokenRepository) {

    private val logger = LoggerFactory.getLogger(FcmTokenService::class.java)

    @Transactional
    fun registerToken(userId: UUID, token: String, platform: String) {
        // 동일 토큰이 이미 있으면 덮어쓰지 않음 (UNIQUE 제약)
        if (fcmTokenRepository.existsByToken(token)) {
            logger.debug("[FCM] Token already registered - userId: $userId")
            return
        }
        fcmTokenRepository.save(
            TableFcmToken(userId = userId, token = token, platform = platform)
        )
        logger.info("[FCM] Token registered - userId: $userId, platform: $platform")
    }

    @Transactional
    fun deleteToken(token: String) {
        fcmTokenRepository.deleteByToken(token)
        logger.info("[FCM] Token deleted")
    }

    @Transactional
    fun deleteAllTokensForUser(userId: UUID) {
        fcmTokenRepository.deleteByUserId(userId)
        logger.info("[FCM] All tokens deleted for userId: $userId")
    }

    fun getTokensByUserId(userId: UUID): List<String> {
        return fcmTokenRepository.findAllByUserId(userId).map { it.token }
    }
}
