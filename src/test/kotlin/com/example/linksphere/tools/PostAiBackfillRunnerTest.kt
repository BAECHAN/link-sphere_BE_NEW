package com.example.linksphere.tools

import com.example.linksphere.domain.feed.FeedEntry
import com.example.linksphere.domain.feed.FeedParser
import com.example.linksphere.domain.feed.FeedSourceRepository
import com.example.linksphere.domain.feed.TableFeedSource
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.AiStatus
import com.example.linksphere.domain.post.PostAIService
import com.example.linksphere.domain.post.PostCreatedEvent
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.domain.post.UrlMetadata
import com.example.linksphere.domain.post.UrlMetadataExtractor
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.LocalDateTime
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class PostAiBackfillRunnerTest {

    // Kotlin에서 ArgumentMatchers.any()/eq()/argThat()을 non-null 파라미터 자리에 그대로 쓰면
    // 반환값이 실제로는 null이라 호출부에서 NullPointerException이 나고, Mockito의 매처 스택까지
    // 어긋나 이 클래스의 다른 테스트까지 연쇄로 깨진다(OrphanImageCleanupRunnerTest와 동일 함정).
    // unchecked cast로 우회한다 - 최상위 함수로 빼면 Kotlin이 synthetic accessor(access$)를
    // 만들면서 반환값에 null 체크를 다시 끼워 넣으므로, 반드시 클래스 멤버로 둬야 한다.
    private fun <T> anyValue(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun <T> eqValue(value: T): T {
        ArgumentMatchers.eq(value)
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun <T> argThatValue(matcher: (T) -> Boolean): T {
        ArgumentMatchers.argThat<T> { matcher(it) }
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun <T> captureValue(captor: ArgumentCaptor<T>): T {
        captor.capture()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @Mock private lateinit var feedSourceRepository: FeedSourceRepository

    @Mock private lateinit var feedParser: FeedParser

    @Mock private lateinit var postAIService: PostAIService

    @InjectMocks private lateinit var runner: PostAiBackfillRunner

    private val botId = UUID.randomUUID()
    private val bot = TableMember(id = botId, email = "bot@link-sphere.local", password = "not-a-real-hash", isBot = true)
    private val postId = UUID.randomUUID()

    private fun post(url: String = "https://example.com/article") = TablePost(
        id = postId,
        userId = botId,
        url = url,
        title = "제목",
    )

    private fun metadata(pageContent: String?) = UrlMetadata(
        title = "제목",
        description = null,
        ogImage = null,
        tags = emptyList(),
        pageContent = pageContent,
    )

    // 대부분의 테스트는 "PENDING/FAILED 고착" 경로를 비워 둬 봇 글 경로만 격리해서 본다.
    private fun stubNoStuckBacklog() {
        `when`(postRepository.findAllByAiStatusInAndCreatedAtBefore(eqValue(listOf(AiStatus.PENDING, AiStatus.FAILED)), anyValue()))
            .thenReturn(emptyList())
    }

    @Test
    fun `dry-run이 기본이라 processAiJob을 호출하지 않는다`() {
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot)
        `when`(postRepository.findAllByUserIdAndAiSummaryIsNull(botId)).thenReturn(listOf(post()))
        stubNoStuckBacklog()
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(emptyList())
        `when`(urlMetadataExtractor.extract("https://example.com/article")).thenReturn(metadata("재크롤링 본문"))

        runner.run(emptyArray())

        verify(postAIService, never()).processAiJob(anyValue())
    }

    @Test
    fun `--commit이면 재크롤링 본문으로 processAiJob을 호출한다`() {
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot)
        `when`(postRepository.findAllByUserIdAndAiSummaryIsNull(botId)).thenReturn(listOf(post()))
        stubNoStuckBacklog()
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(emptyList())
        `when`(urlMetadataExtractor.extract("https://example.com/article")).thenReturn(metadata("재크롤링 본문"))

        runner.run(arrayOf("--commit"))

        verify(postAIService).processAiJob(
            argThatValue<PostCreatedEvent> { event -> event.postId == postId && event.content == "재크롤링 본문" },
        )
    }

    @Test
    fun `재크롤링이 실패하면 RSS 본문으로 폴백한다`() {
        val url = "https://example.com/article"
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot)
        `when`(postRepository.findAllByUserIdAndAiSummaryIsNull(botId)).thenReturn(listOf(post(url)))
        stubNoStuckBacklog()
        `when`(urlMetadataExtractor.extract(url)).thenReturn(metadata(null))
        val source = TableFeedSource(id = UUID.randomUUID(), name = "테스트 소스", url = "https://feed.example.com/rss")
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(listOf(source))
        `when`(feedParser.fetch(source.url)).thenReturn(listOf(FeedEntry(title = "제목", link = url, content = "RSS 폴백 본문")))

        runner.run(arrayOf("--commit"))

        verify(postAIService).processAiJob(
            argThatValue<PostCreatedEvent> { event -> event.content == "RSS 폴백 본문" },
        )
    }

    @Test
    fun `1시간 이내에 생성된 PENDING·FAILED 글은 대상에서 제외한다`() {
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot)
        `when`(postRepository.findAllByUserIdAndAiSummaryIsNull(botId)).thenReturn(emptyList())
        // findAllByAiStatusInAndCreatedAtBefore는 넘어간 커트라인 시각 자체가 검증 대상이므로
        // 일부러 스텁하지 않는다(Mockito는 List 반환 타입에 기본으로 빈 리스트를 돌려준다).
        // targets가 비어 러너가 조기 리턴하므로 feedSourceRepository는 호출되지 않는다 - 그래서
        // 스텁하지 않는다(스텁해도 UnnecessaryStubbingException이 난다).

        runner.run(emptyArray())

        // forClass(...)의 반환 타입은 Java platform type이라 캐스팅 없이 쓰면 captureValue의
        // 반환값에 Kotlin이 checkNotNull을 끼워 넣어 NPE가 난다(OrphanImageCleanupRunnerTest와
        // 동일 이유로 명시적 캐스팅 필요).
        @Suppress("UNCHECKED_CAST")
        val before = ArgumentCaptor.forClass(LocalDateTime::class.java) as ArgumentCaptor<LocalDateTime>
        verify(postRepository).findAllByAiStatusInAndCreatedAtBefore(
            eqValue(listOf(AiStatus.PENDING, AiStatus.FAILED)),
            captureValue(before),
        )
        // 방금 등록돼 self-invoke가 진행 중인 글과 겹치지 않도록 정확히 "1시간 지난 것만" 커트라인으로
        // 잡는지 확인한다 - 너무 taut하게 잡으면(예: now) 진행 중인 잡을 덮칠 수 있다.
        val minutesFromNow = java.time.Duration.between(before.value, LocalDateTime.now()).toMinutes()
        assertTrue(minutesFromNow in 59..61, "커트라인이 1시간 전 근처가 아님: ${minutesFromNow}분 전")
    }

    @Test
    fun `FAILED로 확정된 글도 재분석 대상에 포함한다`() {
        val url = "https://example.com/article"
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot)
        `when`(postRepository.findAllByUserIdAndAiSummaryIsNull(botId)).thenReturn(emptyList())
        `when`(
            postRepository.findAllByAiStatusInAndCreatedAtBefore(
                eqValue(listOf(AiStatus.PENDING, AiStatus.FAILED)),
                anyValue(),
            ),
        ).thenReturn(listOf(post(url)))
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(emptyList())
        `when`(urlMetadataExtractor.extract(url)).thenReturn(metadata("재크롤링 본문"))

        runner.run(arrayOf("--commit"))

        verify(postAIService).processAiJob(
            argThatValue<PostCreatedEvent> { event -> event.postId == postId },
        )
    }
}
