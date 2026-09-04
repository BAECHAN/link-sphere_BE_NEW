package com.example.linksphere.tools

import com.example.linksphere.domain.feed.FeedCrawlItem
import com.example.linksphere.domain.feed.FeedItemProcessor
import com.example.linksphere.domain.feed.FeedItemRepository
import com.example.linksphere.domain.feed.FeedParser
import com.example.linksphere.domain.feed.FeedSourceRepository
import com.example.linksphere.domain.feed.FeedUrlNormalizer
import com.example.linksphere.domain.member.MemberRepository
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * 로컬에서 피드 수집 파이프라인을 검증하는 도구. LambdaSelfInvoker가 로컬(AWS_LAMBDA_FUNCTION_NAME
 * 없음)에서는 self-invoke를 스킵하므로, Stage A/B를 거치는 실제 Lambda 경로는 로컬에서 재현할 수
 * 없다 — 이 러너가 유일한 로컬 E2E 경로다. OrphanImageCleanupRunner와 동일하게 dry-run이 기본이며,
 * 관리자 API가 없는 이 코드베이스에서 admin 성격의 작업은 로컬 실행 도구로만 노출한다.
 *
 * 주의: --commit으로 실제 등록하면 self-invoke가 스킵되므로 등록된 글의 aiStatus가 PENDING에
 * 영구 고착된다(위와 같은 이유). "AI 파이프라인이 도는지" 검증 목적으로는 쓰지 말 것 - 실제로
 * 2026-09-03에 이 방식으로 등록한 10건이 그렇게 남아 프로덕션 봇 글 전수가 요약 없는 상태가 됐었다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,feed-crawl'            (dry-run, 후보만 출력)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,feed-crawl --commit'    (실제 봇 게시글 생성)
 */
@Component
@Profile("feed-crawl")
class FeedCrawlRunner(
    private val feedSourceRepository: FeedSourceRepository,
    private val feedItemRepository: FeedItemRepository,
    private val feedParser: FeedParser,
    private val memberRepository: MemberRepository,
    private val feedItemProcessor: FeedItemProcessor,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val commit = "--commit" in args

        val botId = memberRepository.findFirstByIsBotTrue()?.id
        if (botId == null) {
            println("봇 계정이 없습니다 - sql/create_feed_sources.sql을 먼저 실행하세요.")
            return
        }

        val candidates = mutableListOf<FeedCrawlItem>()
        for (source in feedSourceRepository.findAllByEnabledTrue()) {
            runCatching { feedParser.fetch(source.url) }
                .onSuccess { entries ->
                    println("[${source.name}] ${entries.size}건")
                    entries.take(2).forEach { entry ->
                        candidates.add(FeedCrawlItem(source.id, entry.title, entry.link, entry.content))
                    }
                }
                .onFailure { e -> println("[${source.name}] fetch 실패: ${e.message}") }
        }

        val byNormalizedUrl = candidates.associateBy { FeedUrlNormalizer.normalize(it.url) }
        val alreadySeen =
            feedItemRepository.findAllByNormalizedUrlIn(byNormalizedUrl.keys).map { it.normalizedUrl }.toSet()
        val fresh = byNormalizedUrl.filterKeys { it !in alreadySeen }.values.toList()

        println("전체 후보 ${candidates.size}건, 신규(미수집) ${fresh.size}건")
        fresh.forEach { println("  - ${it.title} | ${it.url}") }

        if (!commit) {
            println("dry-run 모드 - 실제로 등록하려면 --commit을 붙이세요.")
            return
        }

        fresh.forEach { item ->
            runCatching { feedItemProcessor.processFeedItem(botId, item) }
                .onFailure { e -> println("등록 실패 - ${item.url}: ${e.message}") }
        }
        println("${fresh.size}건 처리 완료 (일부는 실패했을 수 있음, 로그 확인)")
    }
}
