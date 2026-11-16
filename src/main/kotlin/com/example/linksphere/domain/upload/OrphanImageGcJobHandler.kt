package com.example.linksphere.domain.upload

import com.example.linksphere.global.job.JobHandler
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

// EventBridge cron(4일마다, link-sphere-feed-crawl 룰을 공유)이 호출하는 고아 이미지 정리.
// 수동 확인용으로 {"linksphereJob":"orphan-image-gc","dryRun":true}를 보내면 지우지 않고
// 로그에 요약과 후보 일부만 남긴다(docs/DEPLOY.md 11장).
@Component
class OrphanImageGcJobHandler(
    private val orphanImageGcService: OrphanImageGcService,
) : JobHandler {

    private val logger = LoggerFactory.getLogger(OrphanImageGcJobHandler::class.java)

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        val dryRun = job.get("dryRun")?.asBoolean() ?: false
        val summary = orphanImageGcService.collect(dryRun)

        if (dryRun) {
            summary.candidates.take(20).forEach { logger.info("[OrphanImageGc] 후보 $it") }
        }
    }

    companion object {
        const val JOB_NAME = "orphan-image-gc"
    }
}
