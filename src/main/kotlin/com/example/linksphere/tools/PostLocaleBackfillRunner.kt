package com.example.linksphere.tools

import com.example.linksphere.domain.post.PostAIService
import com.example.linksphere.domain.post.PostCreatedEvent
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.UrlMetadataExtractor
import org.springframework.boot.CommandLineRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 도쿄 리전 IP 때문에 title·description·aiSummary가 통째로 일본어로 저장된 특정 게시글을
 * 강제로 재수집하는 1회성 복구 도구 (docs/AI-ASYNC-PROCESSING.md §5.9).
 *
 * PostService.updatePost의 재크롤링 경로(title을 비운 PATCH /post/{id})는 이미 값이 있는
 * description을 덮지 않는다(사용자가 손댄 값을 실수로 되돌리지 않기 위한 설계) - 그래서 title은
 * 고쳐져도 description은 일본어로 남는다. 여기서는 대상이 "이미 확인된 오염 데이터"라는 사람의
 * 판단이 전제이므로, OgImageBackfillRunner와 같은 이유로 title·description 모두 무조건 덮어쓴다.
 *
 * postId는 findAllByIdStartingWith로 8자 접두어만으로도 찾는다 - 장애 조사 문서·PR 본문이
 * 개인정보 노출을 피해 postId 앞자리만 남기는 관례(§5.9)를 그대로 인자로 받기 위함이다.
 *
 * PostAiBackfillRunner와 동일하게 aiSummary 재생성은 PostAIService.processAiJob을 로컬에서
 * 직접 동기 호출한다 - self-invoke는 로컬에서 스킵되므로 이벤트 발행만으로는 처리되지 않는다
 * (PostAiBackfillRunner 클래스 주석 참고). title·description을 먼저 덮어써 저장한 뒤에
 * processAiJob을 호출해야 그 안의 WeakTitleDetector 판정이 새 title을 보고 동작한다.
 *
 * OgImageBackfillRunner/PostAiBackfillRunner와 동일하게 dry-run이 기본이며, 관리자 API가
 * 없는 이 코드베이스에서 admin 성격의 작업은 로컬 실행 도구로만 노출한다.
 *
 * 실행: ./gradlew bootRun --args='--spring.profiles.active=secret,post-locale-backfill --post-id=<uuid-or-prefix>,<uuid-or-prefix>'
 *      (dry-run, 보고만)
 *      ./gradlew bootRun --args='--spring.profiles.active=secret,post-locale-backfill --post-id=<uuid-or-prefix>,<uuid-or-prefix> --commit'
 *      (실제 덮어쓰기 + AI 재분석)
 */
@Component
@Profile("post-locale-backfill")
class PostLocaleBackfillRunner(
    private val postRepository: PostRepository,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val postAIService: PostAIService,
) : CommandLineRunner {

    override fun run(args: Array<String>) {
        val commit = "--commit" in args
        val idTokens =
            args.firstOrNull { it.startsWith("--post-id=") }
                ?.substringAfter("=")
                ?.split(",")
                ?.map { it.trim() }
                .orEmpty()

        if (idTokens.isEmpty()) {
            println("--post-id=<uuid-or-prefix>[,<uuid-or-prefix>...] 를 지정하세요.")
            return
        }

        val targets =
            idTokens
                .flatMap { token ->
                    val exact = runCatching { UUID.fromString(token) }.getOrNull()
                    if (exact != null) {
                        postRepository.findById(exact).map { listOf(it) }.orElse(emptyList())
                    } else {
                        postRepository.findAllByIdStartingWith(token)
                    }
                }
                .distinctBy { it.id }
        println("대상 ${targets.size}건 (요청 ${idTokens.size}건)")

        targets.forEach { post ->
            val metadata =
                runCatching {
                    urlMetadataExtractor.extract(post.url, allowProxyFallback = !post.isPrivate)
                }.getOrNull()
            if (metadata == null) {
                println("  [미해결] ${post.title} | ${post.url} - 재수집 실패")
                return@forEach
            }

            println("  ${post.id} | ${post.url}")
            println("    title: ${post.title} -> ${metadata.title}")
            println("    description: ${post.description} -> ${metadata.description}")

            if (!commit) {
                return@forEach
            }

            if (metadata.title.isNotBlank()) post.title = metadata.title
            if (!metadata.description.isNullOrBlank()) post.description = metadata.description
            postRepository.saveAndFlush(post)

            val pageContent = metadata.pageContent
            if (pageContent == null) {
                println("    본문을 못 얻어 AI 재분석은 건너뜀")
                return@forEach
            }

            runCatching {
                postAIService.processAiJob(
                    PostCreatedEvent(
                        postId = post.id!!,
                        userId = post.userId,
                        title = post.title,
                        description = post.description,
                        content = pageContent,
                        existingTags = post.tags.orEmpty(),
                    ),
                )
            }.onFailure { e -> println("    AI 재분석 실패 - ${e.message}") }
        }

        if (!commit) {
            println("dry-run 모드 - 실제로 덮어쓰고 AI를 재분석하려면 --commit을 붙이세요.")
        }
    }
}
