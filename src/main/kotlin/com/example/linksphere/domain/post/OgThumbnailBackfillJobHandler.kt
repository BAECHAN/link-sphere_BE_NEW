package com.example.linksphere.domain.post

import com.example.linksphere.global.job.JobHandler
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import java.util.UUID

// 사용자가 CloudShell에서 직접 부르는 소급(docs/DEPLOY.md "og 이미지 사본 소급"). 입력은 orphan-image-gc의 dryRun처럼 최상위 필드다:
// {"linksphereJob":"og-thumbnail-backfill","limit":1} → 요약 로그의 nextCursor를 받아 {"…","limit":50,"cursor":"<nextCursor>"}.
// limit을 빼면 1건만 처리한다 - 첫 호출은 결과를 보고 넘어가기로 했다(docs/plans/2026-11-25-og-thumbnail.md "소급 실행").
@Component
class OgThumbnailBackfillJobHandler(
    private val ogThumbnailBackfillService: OgThumbnailBackfillService,
) : JobHandler {

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        val limit = (job.get("limit")?.asInt() ?: 1).coerceAtLeast(1)
        val cursor = job.get("cursor")?.asText()?.let(UUID::fromString)

        ogThumbnailBackfillService.run(limit, cursor)
    }

    companion object {
        const val JOB_NAME = "og-thumbnail-backfill"
    }
}
