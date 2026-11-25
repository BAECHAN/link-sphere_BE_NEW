package com.example.linksphere.domain.post

import com.example.linksphere.global.common.BatchJobLimits
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import java.net.URI
import java.util.UUID

/** 소급 한 번의 결과. [failures]는 `이유@사이트` → 건수다. [nextCursor]를 다음 호출의 cursor로 넘긴다. */
data class OgThumbnailBackfillSummary(
    val processed: Int,
    val stored: Int,
    val failures: Map<String, Int>,
    val remaining: Long,
    val nextCursor: UUID?,
    val deadlineReached: Boolean,
)

/**
 * og 이미지는 있는데 사본이 없는 글에 사본을 만든다. 사용자가 CloudShell에서 잡 `og-thumbnail-backfill`을 직접 부른다
 * (docs/DEPLOY.md "og 이미지 사본 소급"). 첫 호출은 1건으로 결과를 보고, 남은 수가 0이 될 때까지 cursor를 넘겨 다시 부른다.
 *
 * - 차례로 하나씩 처리한다. 병렬로 돌리지 않아 계정의 Lambda 동시 실행 10칸 가운데 이 호출 하나만 쓴다.
 * - 90초 마감(BatchJobLimits)은 글 하나를 시작하기 전에 본다. 넘었으면 남은 글은 다음 호출로 넘긴다.
 * - 못 만든 글(WebP·404 등)은 표시하지 않는다 - 컬럼이 null이면 FE가 원본을 그리고, null이 아니면 사본으로 본다. 대신 cursor가
 *   지나간 글은 다시 읽지 않으므로 영구 실패가 있어도 소급은 끝난다. cursor 없이 다시 부르면 실패한 글을 처음부터 다시 시도한다.
 * - 결과는 잡이 응답으로 돌려줄 수 없어(LambdaHandler는 잡에 "ok"만 낸다) 마지막 요약 로그 한 줄로 남긴다 - `--log-type Tail`이
 *   돌려주는 마지막 4KB에 들어간다.
 */
@Service
class OgThumbnailBackfillService(
    private val postRepository: PostRepository,
    private val ogThumbnailService: OgThumbnailService,
) {

    private val logger = LoggerFactory.getLogger(OgThumbnailBackfillService::class.java)

    // UUID는 Postgres에서 바이트 순으로 정렬된다. 모두 0이면 가장 앞이다
    private val firstCursor = UUID(0L, 0L)

    fun run(limit: Int, cursor: UUID?): OgThumbnailBackfillSummary = run(limit, cursor, System::currentTimeMillis)

    internal fun run(limit: Int, cursor: UUID?, currentTimeMillis: () -> Long): OgThumbnailBackfillSummary {
        val deadline = currentTimeMillis() + BatchJobLimits.DEADLINE_MILLIS
        val candidates = postRepository.findOgThumbnailCandidatesAfter(cursor ?: firstCursor, Limit.of(limit))

        var processed = 0
        var stored = 0
        val failures = sortedMapOf<String, Int>()
        var lastProcessed = cursor
        var deadlineReached = false

        for (candidate in candidates) {
            if (currentTimeMillis() > deadline) {
                deadlineReached = true
                break
            }

            val result =
                runCatching { ogThumbnailService.createThumbnail(candidate.id, candidate.ogImage) }
                    .getOrElse { e ->
                        logger.error("[OgThumbnailBackfill] 사본 만들기 실패 - postId: ${candidate.id}", e)
                        OgThumbnailResult.NotStored("exception-${e.javaClass.simpleName}")
                    }

            processed++
            lastProcessed = candidate.id

            when (result) {
                is OgThumbnailResult.Stored -> stored++
                is OgThumbnailResult.NotStored -> failures.merge("${result.reason}@${hostOf(candidate.ogImage)}", 1, Int::plus)
            }
        }

        val remaining = if (candidates.isEmpty()) 0L else postRepository.countOgThumbnailCandidatesAfter(lastProcessed ?: firstCursor)
        val summary = OgThumbnailBackfillSummary(processed, stored, failures, remaining, lastProcessed, deadlineReached)

        logger.info(
            "[OgThumbnailBackfill] 완료 - processed=$processed, stored=$stored, failed=${processed - stored}, failures=$failures, " +
                "remaining=$remaining, nextCursor=$lastProcessed, deadlineReached=$deadlineReached",
        )
        return summary
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull() ?: "unknown"
}
