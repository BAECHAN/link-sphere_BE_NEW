package com.example.linksphere.tools

import com.example.linksphere.domain.feed.FeedParser
import com.example.linksphere.domain.feed.FeedSourceRepository
import com.example.linksphere.domain.feed.FeedUrlNormalizer
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.post.AiStatus
import com.example.linksphere.domain.post.PostAIService
import com.example.linksphere.domain.post.PostCreatedEvent
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.UrlMetadataExtractor
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * aiSummary가 비어 있거나 aiStatus가 PENDING에 고착된 게시글에 뒤늦게 AI 요약을 채우는
 * 1회성 복구 도구.
 *
 * 세 가지 원인으로 게시글에 aiSummary가 비어 있을 수 있다: (1) 크롤링이 막혀 애초에 AI
 * 이벤트가 발행되지 않은 경우(aiStatus=NONE, 봇 글) - PostService.createPost의
 * fallbackContent 배선(2026-09) 이후로는 신규 등록 건에서 재발하지 않는다. (2)
 * FeedCrawlRunner를 로컬(--commit)로 돌려 self-invoke가 스킵된 경우(aiStatus=PENDING
 * 고착, 봇 글) - FeedCrawlRunner 클래스 주석 참고. (3) self-invoke 전환(2026-07~08) 이전
 * 컨테이너 freeze 결함으로 사람이 등록한 글이 PENDING에 고착된 경우. 셋 다 "content를 다시
 * 만들어 AI 잡을 돌린다"는 같은 처리라 분기 없이 한 루프에서 처리한다.
 *
 * 로컬에서는 LambdaSelfInvoker가 self-invoke를 스킵하므로(AWS_LAMBDA_FUNCTION_NAME 없음)
 * eventPublisher.publishEvent로 위임하면 지금 이 복구 대상을 만든 바로 그 문제에 다시 빠진다.
 * 그래서 프로덕션에서 LambdaHandler.handleAiJob이 하는 것과 동일하게 PostAIService.processAiJob을
 * 직접 동기 호출한다 - 이 경로에서는 "PENDING으로 리셋 후 콜백을 기다리는" 중간 상태가 관찰될
 * 구간이 아예 없으므로(processAiJob이 COMPLETED/FAILED를 직접 쓴다) PostService.updatePost의
 * URL 재분석 흐름과 달리 PENDING 리셋은 생략한다.
 *
 * OrphanImageCleanupRunner와 동일하게 dry-run이 기본이며, 관리자 API가 없는 이 코드베이스에서
 * admin 성격의 작업은 로컬 실행 도구로만 노출한다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,ai-backfill'            (dry-run, 보고만)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,ai-backfill --commit'    (실제 AI 분석 실행)
 */
@Component
@Profile("ai-backfill")
class PostAiBackfillRunner(
    private val memberRepository: MemberRepository,
    private val postRepository: PostRepository,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val feedSourceRepository: FeedSourceRepository,
    private val feedParser: FeedParser,
    private val postAIService: PostAIService,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val commit = "--commit" in args

        val bot = memberRepository.findFirstByIsBotTrue()
        if (bot == null) {
            println("봇 계정이 없습니다 - sql/create_feed_sources.sql을 먼저 실행하세요.")
            return
        }

        // (1) 봇 글 중 aiSummary가 빈 것: 크롤링 403으로 aiStatus=NONE인 건까지 잡으려면
        //     상태가 아니라 요약 유무로 봐야 한다.
        // (2) 소유자 무관하게 PENDING에 고착된 것: 사람이 등록한 잔존 건이 여기 해당한다.
        //     방금 등록돼 정상 처리 중인 글을 덮치지 않도록 1시간 지난 것만 본다 - self-invoke가
        //     진행 중인 글을 여기서 동시에 분석하면 같은 post에 두 트랜잭션이 붙는다.
        val targets =
            (
                postRepository.findAllByUserIdAndAiSummaryIsNull(bot.id!!) +
                    postRepository.findAllByAiStatusAndCreatedAtBefore(
                        AiStatus.PENDING,
                        LocalDateTime.now().minusHours(1),
                    )
                ).distinctBy { it.id }
        if (targets.isEmpty()) {
            println("복구 대상 게시글이 없습니다.")
            return
        }

        // 활성 피드 소스를 한 번씩만 fetch해 정규화 URL → 본문 인덱스를 만든다. 봇 글의
        // 재크롤링이 실패했을 때(원인 1)의 폴백으로 쓴다. 사람 글은 이 인덱스에 없는 URL이라
        // 재크롤링만 시도된다.
        val feedContentByNormalizedUrl =
            feedSourceRepository.findAllByEnabledTrue()
                .flatMap { source -> runCatching { feedParser.fetch(source.url) }.getOrDefault(emptyList()) }
                .associate { entry -> FeedUrlNormalizer.normalize(entry.link) to entry.content }

        println("대상 ${targets.size}건")

        var resolved = 0
        var unresolved = 0
        targets.forEach { post ->
            val recrawled = runCatching { urlMetadataExtractor.extract(post.url).pageContent }.getOrNull()
            val content = recrawled ?: feedContentByNormalizedUrl[FeedUrlNormalizer.normalize(post.url)]

            if (content == null) {
                unresolved++
                println("  [미해결] ${post.title} | ${post.url}")
                return@forEach
            }

            resolved++
            val source = if (recrawled != null) "재크롤링" else "RSS 폴백"
            // 본문 원문은 찍지 않는다 - 대상이 봇 글 한정이 아니라 전 사용자로 넓어져 isPrivate
            // 글의 내용이 로그에 남을 수 있다. 길이만으로도 디버깅에 충분하다.
            println("  [$source] ${post.title} | ${post.url} | contentLength=${content.length}")

            if (commit) {
                runCatching {
                    postAIService.processAiJob(
                        PostCreatedEvent(
                            postId = post.id!!,
                            userId = post.userId,
                            title = post.title,
                            description = post.description,
                            content = content,
                            existingTags = post.tags.orEmpty(),
                        ),
                    )
                }.onFailure { e -> println("    AI 분석 실패 - ${post.url}: ${e.message}") }
            }
        }

        println("해결 가능 ${resolved}건, 미해결 ${unresolved}건")
        if (!commit) {
            println("dry-run 모드 - 실제로 AI 분석을 실행하려면 --commit을 붙이세요.")
        }
    }
}
