package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.MemberRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * EventBridge cron(매일, KST 03:00) → LambdaHandler("account-purge")가 호출하는 진입점.
 * 유예 14일이 지난 탈퇴 신청을 실제로 익명화한다(AccountDeletionService.purge).
 *
 * FeedCrawlService.collectAndDispatch와 같은 shape - 회원별로 별도 빈(AccountDeletionService)의
 * @Transactional 메서드를 호출해 한 명 실패가 나머지를 막지 않게 하고, 90초 데드라인을 두어
 * 남은 건은 다음날 실행으로 미룬다.
 */
@Service
class AccountPurgeService(
    private val memberRepository: MemberRepository,
    private val accountDeletionService: AccountDeletionService,
) {

    private val logger = LoggerFactory.getLogger(AccountPurgeService::class.java)

    companion object {
        private const val BATCH_SIZE = 200
        private const val DEADLINE_MILLIS = 90_000L
    }

    data class PurgeSummary(val purged: Int, val skipped: Int, val failed: Int)

    fun purgeExpired(now: Instant = Instant.now()): PurgeSummary {
        val cutoff = now.minus(AccountDeletionService.GRACE_PERIOD)
        val ids = memberRepository.findIdsPendingPurge(cutoff, PageRequest.of(0, BATCH_SIZE))

        var purged = 0
        var skipped = 0
        var failed = 0
        val deadline = System.currentTimeMillis() + DEADLINE_MILLIS

        for (id in ids) {
            if (System.currentTimeMillis() > deadline) {
                logger.warn("[AccountPurge] 90초 마감 초과 - 남은 대상은 다음 실행으로 미룸")
                break
            }

            runCatching { accountDeletionService.purge(id, cutoff) }
                .onSuccess { claimed -> if (claimed) purged++ else skipped++ }
                .onFailure { e ->
                    failed++
                    logger.error("[AccountPurge] 퍼지 실패 - memberId: $id", e)
                }
        }

        logger.info("[AccountPurge] 완료 - purged=$purged, skipped=$skipped, failed=$failed, 대상=${ids.size}")
        return PurgeSummary(purged, skipped, failed)
    }
}
