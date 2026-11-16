package com.example.linksphere.infra.fcm

import com.example.linksphere.domain.auth.MemberSessionService
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.MessagingErrorCode
import com.google.firebase.messaging.MulticastMessage
import com.google.firebase.messaging.Notification
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class FcmService(
    private val fcmTokenRepository: FcmTokenRepository,
    private val memberSessionService: MemberSessionService,
) {

    private val logger = LoggerFactory.getLogger(FcmService::class.java)

    // 호출부(CommentPostProcessService.processCommentJob)의 트랜잭션과 떼어 놓는다 - 참여한 채 토큰 정리 쿼리가 DB 오류를 내면,
    // 아래 catch가 예외를 삼켜도 Hibernate가 그 트랜잭션을 롤백 전용으로 표시해 바깥 커밋이 UnexpectedRollbackException으로
    // 실패했다(hibernate-core 6.6.36 ExceptionConverterImpl.rollbackIfNecessary, spring-tx 6.2.14
    // AbstractPlatformTransactionManager.commit) - 링크 프리뷰 갱신까지 사라지고 Lambda 재시도로 알림이 다시 나간다
    // (FcmServiceIntegrationTest). 이렇게 떼면 실패는 이 트랜잭션에서 끝나고 커밋 실패 예외는 호출부의 runCatching이 받는다.
    // RateLimitService의 REQUIRES_NEW와 같은 방식이다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun sendToUser(userId: UUID, title: String, body: String, data: Map<String, String> = emptyMap()) {
        // Firebase가 초기화되지 않았으면 조용히 스킵
        if (FirebaseApp.getApps().isEmpty()) {
            logger.warn("[FCM] Firebase not initialized — skipping notification to userId: $userId")
            return
        }

        try {
            val tokens = removeStaleTokens(userId)
            if (tokens.isEmpty()) {
                logger.debug("[FCM] No tokens for userId: $userId — skipping")
                return
            }

            val message = MulticastMessage.builder()
                .setNotification(
                    Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build(),
                )
                .putAllData(data)
                .addAllTokens(tokens)
                .build()

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
        } catch (e: Exception) {
            // 알림은 이제 요청 경로 밖(CommentPostProcessService 등 커밋 후 비동기 job)에서만
            // 호출된다. 전송 계층 예외(FirebaseMessagingException 외)까지 여기서 흡수해야
            // 후처리 job의 나머지 작업(링크 프리뷰 갱신)이 알림 실패로 함께 죽지 않는다.
            logger.error("[FCM] Failed to send notification to userId: $userId", e)
        }
    }

    /**
     * 세션이 죽은(로그아웃·재사용탐지·비밀번호변경·자연만료) 기기의 토큰을 지우고, 보낼 토큰만 돌려준다. 필터만 하고 지우지
     * 않으면 그 토큰은 발송 대상에서 계속 빠지기만 할 뿐 FCM의 UNREGISTERED 응답(실패 정리 로직)을 받을 기회가 없어
     * 영원히 안 지워진다.
     *
     * 어느 계열이 살아 있는지는 auth에 묻는다. 토큰을 먼저 읽고 계열을 나중에 읽는다 - 그 사이 새로 로그인한 기기의 토큰은
     * 읽은 목록에 없어 지우지 않고, 이번 발송에서만 빠진다. 계열을 모르는 토큰(컬럼 도입 전 행)은 예전처럼 죽은 것으로 본다.
     */
    private fun removeStaleTokens(userId: UUID): List<String> {
        val registered = fcmTokenRepository.findAllByUserId(userId)

        if (registered.isEmpty()) {
            return emptyList()
        }

        val liveFamilyIds = memberSessionService.findLiveFamilyIds(userId)
        val (live, stale) = registered.partition { it.sessionFamilyId in liveFamilyIds }

        if (stale.any { it.sessionFamilyId == null }) {
            fcmTokenRepository.deleteByUserIdAndSessionFamilyIdIsNull(userId)
        }

        val deadFamilyIds = stale.mapNotNull { it.sessionFamilyId }.toSet()

        if (deadFamilyIds.isNotEmpty()) {
            fcmTokenRepository.deleteByUserIdAndSessionFamilyIdIn(userId, deadFamilyIds)
        }

        return live.map { it.token }
    }

    private fun isInvalidTokenError(exception: FirebaseMessagingException?): Boolean {
        if (exception == null) return false
        return exception.messagingErrorCode in listOf(
            MessagingErrorCode.UNREGISTERED,
            MessagingErrorCode.INVALID_ARGUMENT,
        )
    }
}
