package com.example.linksphere.domain.feed

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.infra.aws.FeedJobDispatcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * EventBridge cron(1일 1회) → LambdaHandler("feed-crawl")가 호출하는 Stage A + Stage B 진입점.
 *
 * 피드 fetch(최악 8건 × 10s)와 항목별 크롤링(최악 15건 × 수십 초)을 한 Lambda 호출 안에서 다 하면
 * 120초 타임아웃을 넘긴다. 그래서 AI 분석과 같은 shape로 쪼갠다 — Stage A는 후보 URL만 모아
 * 5건씩 self-invoke로 넘기고, Stage B가 실제 게시글 생성을 처리한다.
 */
@Service
class FeedCrawlService(
    private val feedSourceRepository: FeedSourceRepository,
    private val feedItemRepository: FeedItemRepository,
    private val feedParser: FeedParser,
    private val memberRepository: MemberRepository,
    private val feedJobDispatcher: FeedJobDispatcher,
    private val feedItemProcessor: FeedItemProcessor,
) {

    private val logger = LoggerFactory.getLogger(FeedCrawlService::class.java)

    companion object {
        private const val MAX_ITEMS_PER_SOURCE = 2
        private const val MAX_ITEMS_TOTAL = 15
        private const val CHUNK_SIZE = 5
        private const val DEADLINE_MILLIS = 90_000L
    }

    // Stage A: 피드 소스를 순회하며 후보 URL만 모으고 실제 게시글은 만들지 않는다.
    // DB 쓰기는 feed_sources.last_fetched_at/last_error 갱신뿐이라 항목별 @Transactional이 필요 없다
    // (source 단위 실패 격리는 runCatching으로 충분 - 각 save() 호출 자체가 이미 독립 트랜잭션이다).
    fun collectAndDispatch() {
        val botId = memberRepository.findFirstByIsBotTrue()?.id
        if (botId == null) {
            logger.error("[FeedCrawl] 봇 계정이 없음 - 수집 중단 (sql/create_feed_sources.sql 실행 여부 확인)")
            return
        }

        val deadline = System.currentTimeMillis() + DEADLINE_MILLIS
        val candidates = mutableListOf<FeedCrawlItem>()

        for (source in feedSourceRepository.findAllByEnabledTrue()) {
            if (System.currentTimeMillis() > deadline) {
                logger.warn("[FeedCrawl] 90초 마감 초과 - 남은 소스는 다음 실행으로 미룸")
                break
            }
            if (candidates.size >= MAX_ITEMS_TOTAL) break

            runCatching {
                feedParser.fetch(source.url)
                    .take(MAX_ITEMS_PER_SOURCE)
                    .forEach { entry -> candidates.add(FeedCrawlItem(source.id, entry.title, entry.link)) }
                source.lastFetchedAt = LocalDateTime.now()
                source.lastError = null
            }.onFailure { e ->
                logger.warn("[FeedCrawl] 피드 fetch 실패 - ${source.name}: ${e.message}")
                source.lastError = e.message?.take(500)
            }
            feedSourceRepository.save(source)
        }

        if (candidates.isEmpty()) {
            logger.info("[FeedCrawl] 신규 후보 없음")
            return
        }

        // 1차 방어(성능): 이미 원장에 있는 URL은 self-invoke 자체를 아낀다.
        // 2차 방어(정합성)는 FeedItemProcessor.claim의 ON CONFLICT DO NOTHING이 맡는다.
        val byNormalizedUrl = candidates.associateBy { FeedUrlNormalizer.normalize(it.url) }
        val alreadySeen =
            feedItemRepository.findAllByNormalizedUrlIn(byNormalizedUrl.keys)
                .map { it.normalizedUrl }
                .toSet()
        val fresh = byNormalizedUrl.filterKeys { it !in alreadySeen }.values.toList().take(MAX_ITEMS_TOTAL)

        if (fresh.isEmpty()) {
            logger.info("[FeedCrawl] 후보가 모두 이미 수집된 URL - 종료")
            return
        }

        val chunks = fresh.chunked(CHUNK_SIZE)
        chunks.forEach { chunk -> feedJobDispatcher.dispatch(FeedItemJobEvent(botId = botId, items = chunk)) }
        logger.info("[FeedCrawl] 후보 ${fresh.size}건, ${chunks.size}개 chunk로 dispatch")
    }

    // Stage B: FeedJobDispatcher가 위임한 self-invoke 안에서 LambdaHandler가 직접 호출하는 처리부.
    // 항목마다 별도 빈(FeedItemProcessor)을 통해 호출해야 @Transactional이 실제로 적용된다 —
    // 이 클래스 안에서 직접 호출하면 Spring 프록시를 우회한다(FeedItemProcessor 클래스 주석 참고).
    fun processFeedItemJob(event: FeedItemJobEvent) {
        event.items.forEach { item ->
            runCatching { feedItemProcessor.processFeedItem(event.botId, item) }
                .onFailure { logger.error("[FeedCrawl] 항목 처리 실패 - url: ${item.url}", it) }
        }
    }
}
