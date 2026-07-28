package com.example.linksphere.infra.fcm

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FcmTokenService(private val fcmTokenRepository: FcmTokenRepository) {

    private val logger = LoggerFactory.getLogger(FcmTokenService::class.java)

    @Transactional
    fun registerToken(userId: UUID, token: String, platform: String) {
        val existing = fcmTokenRepository.findByToken(token)
        if (existing != null) {
            // 같은 기기에서 계정을 전환하면 토큰이 이전 사용자에게 묶인 채 남아
            // 이전 사용자의 알림이 새 사용자 기기로 가는 문제가 있어, 소유자를 갱신한다.
            if (existing.userId != userId) {
                fcmTokenRepository.deleteByToken(token)
                fcmTokenRepository.save(TableFcmToken(userId = userId, token = token, platform = platform))
                logger.info("[FCM] Token reassigned to new userId: $userId")
            } else {
                logger.debug("[FCM] Token already registered - userId: $userId")
            }
            return
        }
        fcmTokenRepository.save(
            TableFcmToken(userId = userId, token = token, platform = platform),
        )
        logger.info("[FCM] Token registered - userId: $userId, platform: $platform")
    }

    @Transactional
    fun deleteToken(userId: UUID, token: String) {
        fcmTokenRepository.deleteByUserIdAndToken(userId, token)
        logger.info("[FCM] Token deleted - userId: $userId")
    }

    @Transactional
    fun deleteAllTokensForUser(userId: UUID) {
        fcmTokenRepository.deleteByUserId(userId)
        logger.info("[FCM] All tokens deleted for userId: $userId")
    }

    fun getTokensByUserId(userId: UUID): List<String> = fcmTokenRepository.findAllByUserId(userId).map { it.token }
}
