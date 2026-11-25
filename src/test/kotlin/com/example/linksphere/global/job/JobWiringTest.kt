package com.example.linksphere.global.job

import com.example.linksphere.domain.auth.AccountPurgeJobHandler
import com.example.linksphere.domain.comment.CommentPostProcessEvent
import com.example.linksphere.domain.comment.CommentPostProcessJobHandler
import com.example.linksphere.domain.feed.FeedCrawlItem
import com.example.linksphere.domain.feed.FeedCrawlJobHandler
import com.example.linksphere.domain.feed.FeedItemJobEvent
import com.example.linksphere.domain.feed.FeedItemJobHandler
import com.example.linksphere.domain.post.AiAnalysisJobHandler
import com.example.linksphere.domain.post.OgThumbnailBackfillJobHandler
import com.example.linksphere.domain.post.OgThumbnailJobHandler
import com.example.linksphere.domain.post.PostCreatedEvent
import com.example.linksphere.domain.upload.OrphanImageGcJobHandler
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import java.util.UUID

/**
 * 뒷일 전달에서 바뀌면 운영이 조용히 깨지는 값들을 고정한다.
 *
 * - 잡 이름: 배포 순간 떠 있는 self-invoke와 EventBridge 룰의 입력이 이 이름을 쓴다. 바꾸면 그 호출은 잡으로 인식되지 않아
 *   HTTP 요청 경로로 흘러 403으로 끝난다(LambdaHandler.dispatch).
 * - 맡기는 JSON의 모양: 예전 `*JobPayload` data class가 만들던 것과 글자까지 같아야 한다.
 * - 로컬 도구 프로필의 기본값: 빠진 프로필은 도구가 만드는 글마다 Gemini 호출과 크롤링을 돌린다.
 */
class JobWiringTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `잡 이름은 운영에서 쓰는 여섯 개 그대로다`() {
        assertEquals(
            listOf("ai-analysis", "comment-postprocess", "feed-crawl", "feed-item", "account-purge", "orphan-image-gc"),
            listOf(
                AiAnalysisJobHandler.JOB_NAME,
                CommentPostProcessJobHandler.JOB_NAME,
                FeedCrawlJobHandler.JOB_NAME,
                FeedItemJobHandler.JOB_NAME,
                AccountPurgeJobHandler.JOB_NAME,
                OrphanImageGcJobHandler.JOB_NAME,
            ),
        )
    }

    // 피드 카드용 og 이미지 사본 잡 둘(2026-11-25). 소급 잡 이름은 사용자가 CloudShell에서 보내는 입력에도 들어간다(docs/DEPLOY.md)
    @Test
    fun `og 이미지 사본 잡 이름은 og-thumbnail과 og-thumbnail-backfill이다`() {
        assertEquals(
            listOf("og-thumbnail", "og-thumbnail-backfill"),
            listOf(OgThumbnailJobHandler.JOB_NAME, OgThumbnailBackfillJobHandler.JOB_NAME),
        )
    }

    @Test
    fun `맡기는 JSON은 linksphereJob과 event 순서 그대로다`() {
        val postId = UUID.fromString("00000000-0000-4000-8000-000000000001")
        val userId = UUID.fromString("00000000-0000-4000-8000-000000000002")
        val sourceId = UUID.fromString("00000000-0000-4000-8000-000000000003")

        assertEquals(
            """{"linksphereJob":"ai-analysis","event":{"postId":"$postId","userId":"$userId","title":"제목","description":null,""" +
                """"content":"본문","existingTags":["a"]}}""",
            mapper.writeValueAsString(JobMessage.of("ai-analysis", PostCreatedEvent(postId, userId, "제목", null, "본문", listOf("a")))),
        )
        assertEquals(
            """{"linksphereJob":"comment-postprocess","event":{"commentId":"$postId","notify":true}}""",
            mapper.writeValueAsString(JobMessage.of("comment-postprocess", CommentPostProcessEvent(postId, true))),
        )
        assertEquals(
            """{"linksphereJob":"feed-item","event":{"botId":"$userId","items":[{"sourceId":"$sourceId","title":"글","url":"https://example.com/1","content":null}]}}""",
            mapper.writeValueAsString(
                JobMessage.of("feed-item", FeedItemJobEvent(userId, listOf(FeedCrawlItem(sourceId, "글", "https://example.com/1")))),
            ),
        )
    }

    @Test
    fun `기본값은 같은 프로세스 처리이고 로컬 도구 프로필은 모두 생략이다`() {
        val documents = YamlPropertySourceLoader().load("application", ClassPathResource("application.yml"))
        val base = documents.first()
        val tools = documents.single { it.getProperty("spring.config.activate.on-profile") != null }

        assertEquals("in-process", base.getProperty("app.job.publisher"))
        assertEquals("skip", tools.getProperty("app.job.publisher"))

        val listed = tools.getProperty("spring.config.activate.on-profile").toString().split("|").map { it.trim() }.toSet()
        val toolProfiles =
            ClassFileImporter()
                .withImportOption(ImportOption.DoNotIncludeTests())
                .importPackages("com.example.linksphere.tools")
                .filter { it.isAnnotatedWith(Profile::class.java) }
                .flatMap { it.getAnnotationOfType(Profile::class.java).value.toList() }
                .toSet()

        assertEquals(toolProfiles, listed)
    }
}
