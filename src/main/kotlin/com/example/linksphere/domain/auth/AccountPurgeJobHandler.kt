package com.example.linksphere.domain.auth

import com.example.linksphere.domain.member.AccountPurgeService
import com.example.linksphere.global.job.JobHandler
import com.example.linksphere.global.ratelimit.RateLimitService
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component

// EventBridge cron(4일마다, link-sphere-feed-crawl 룰을 feed-crawl과 공유)이 직접
// 호출하는 진입점 - 탈퇴 유예 14일이 지난 계정을 익명화하고, 끝난 레이트리밋 창의 행을 지운다.
// 회원 한 명이 실패해도 나머지는 계속 처리하므로(AccountPurgeService 내부의 runCatching)
// 여기서 별도 예외 처리는 필요 없다. 행 삭제가 실패하면 잡 오류로 드러나고, 다시 돌아도 결과가 같다.
@Component
class AccountPurgeJobHandler(
    private val accountPurgeService: AccountPurgeService,
    private val rateLimitService: RateLimitService,
) : JobHandler {

    override val jobName = JOB_NAME

    override fun handle(job: JsonNode) {
        accountPurgeService.purgeExpired()
        rateLimitService.purgeExpired()
    }

    companion object {
        const val JOB_NAME = "account-purge"
    }
}
