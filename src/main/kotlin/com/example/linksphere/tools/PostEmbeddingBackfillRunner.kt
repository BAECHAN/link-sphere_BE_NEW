package com.example.linksphere.tools

import com.example.linksphere.domain.post.PostAIService
import com.example.linksphere.domain.post.PostEmbeddingText
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.infra.ai.GeminiService
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 임베딩이 없는 기존 게시글에 뒤늦게 채우는 1회성 복구 도구
 * (docs/plans/2026-09-27-search-quality.md 2c).
 *
 * 임베딩이 비어 있는 원인은 둘이다: (1) 이 기능이 배포되기 전에 이미 있던 글(대다수)
 * (2) 크롤링이 실패해 AI 잡 자체가 발행되지 않은 글(aiStatus=NONE, PostAiBackfillRunner의
 * 대상과 겹친다 - 그 러너를 먼저 돌려 요약을 채운 뒤 이 러너를 돌리면 요약까지 임베딩
 * 텍스트에 포함된다).
 *
 * PostAiBackfillRunner와 동일하게 dry-run이 기본이고, Gemini 무료 티어 하루 한도를
 * 한 번에 다 쓰지 않도록 --limit을 지원한다(docs/AI-ASYNC-PROCESSING.md §5.4 사고 참고).
 * 요약 재분석(processAiJob)을 다시 돌리지 않고 임베딩만 직접 채운다 - 이미 있는 요약
 * 쿼터를 다시 쓰지 않기 위해서다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,embedding-backfill'                     (dry-run, 보고만)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,embedding-backfill --commit'              (실제 임베딩 생성)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,embedding-backfill --limit=50 --commit'   (건수 분할 실행)
 */
@Component
@Profile("embedding-backfill")
class PostEmbeddingBackfillRunner(
    private val postRepository: PostRepository,
    private val geminiService: GeminiService,
    private val postAIService: PostAIService,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val commit = "--commit" in args
        val limit = args.firstOrNull { it.startsWith("--limit=") }?.substringAfter("=")?.toIntOrNull()

        // 방금 등록돼 AI 잡이 진행 중인 글을 동시에 건드리지 않도록 1시간 지난 것만 본다
        // (PostAiBackfillRunner와 동일한 이유).
        val targets =
            postRepository.findAllWithoutEmbeddingCreatedBefore(LocalDateTime.now().minusHours(1))
                .let { if (limit != null) it.take(limit) else it }

        if (targets.isEmpty()) {
            println("임베딩 채울 게시글이 없습니다.")
            return
        }
        println("대상 ${targets.size}건")

        if (!commit) {
            // dry-run은 대상만 보여준다 - Gemini는 절대 호출하지 않는다. PostAiBackfillRunner와
            // 동일한 원칙(무료 티어 쿼터를 "미리보기"로 쓰지 않는다).
            targets.forEach { post -> println("  [대상] ${post.title} | ${post.url}") }
            println("dry-run 모드 - 실제로 임베딩을 생성하려면 --commit을 붙이세요.")
            return
        }

        var succeeded = 0
        var failed = 0
        targets.forEach { post ->
            val embedding = runCatching { geminiService.embedDocument(PostEmbeddingText.document(post)) }.getOrNull()
            if (embedding == null) {
                failed++
                println("  [실패] ${post.title} | ${post.url}")
                return@forEach
            }

            runCatching { postAIService.saveEmbedding(post.id!!, embedding) }
                .onSuccess {
                    succeeded++
                    println("  [성공] ${post.title} | ${post.url}")
                }
                .onFailure { e -> println("  [저장 실패] ${post.url}: ${e.message}") }
        }

        println("성공 $succeeded 건, 실패 $failed 건")
    }
}
