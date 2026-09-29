package com.example.linksphere.infra.fcm

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FcmTokenService(private val fcmTokenRepository: FcmTokenRepository) {

    private val logger = LoggerFactory.getLogger(FcmTokenService::class.java)

    // sessionFamilyId: 이 토큰을 등록한 access 토큰이 속한 세션의 회전 계열
    // (SecurityUtils.getSessionFamilyId()). 같은 토큰이 이미 있으면(기기 재사용·계정
    // 전환 포함) 소유자·계열을 upsert로 덮어쓴다 - 발송 쪽 deleteStaleTokensForUser와
    // 겹쳐도 단일 SQL 문이라 안전하다.
    @Transactional
    fun registerToken(userId: UUID, token: String, platform: String, sessionFamilyId: UUID?) {
        fcmTokenRepository.upsertToken(UUID.randomUUID(), userId, token, platform, sessionFamilyId)
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
