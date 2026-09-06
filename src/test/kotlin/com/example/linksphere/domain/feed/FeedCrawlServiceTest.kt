package com.example.linksphere.domain.feed

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.infra.aws.FeedJobDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyCollection
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class FeedCrawlServiceTest {

    @Mock private lateinit var feedSourceRepository: FeedSourceRepository

    @Mock private lateinit var feedItemRepository: FeedItemRepository

    @Mock private lateinit var feedParser: FeedParser

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var feedJobDispatcher: FeedJobDispatcher

    @Mock private lateinit var feedItemProcessor: FeedItemProcessor

    @InjectMocks private lateinit var feedCrawlService: FeedCrawlService

    private val botId = UUID.randomUUID()

    private fun bot() = TableMember(id = botId, email = "bot@link-sphere.local", password = "!", isBot = true)

    @Test
    fun `봇 계정이 없으면 소스 조회 없이 즉시 중단한다`() {
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(null)

        feedCrawlService.collectAndDispatch()

        verifyNoInteractions(feedSourceRepository)
        verifyNoInteractions(feedJobDispatcher)
    }

    @Test
    fun `새 후보가 없으면 dispatch하지 않는다`() {
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot())
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(emptyList())

        feedCrawlService.collectAndDispatch()

        verifyNoInteractions(feedJobDispatcher)
    }

    @Test
    fun `이미 원장에 있는 URL은 dispatch 후보에서 제외한다`() {
        val source =
            TableFeedSource(id = UUID.randomUUID(), name = "GeekNews", url = "https://news.hada.io/rss/news")
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot())
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(listOf(source))
        `when`(feedParser.fetch(source.url))
            .thenReturn(listOf(FeedEntry("이미 처리된 글", "https://example.com/seen")))
        `when`(feedItemRepository.findAllByNormalizedUrlIn(anyCollection()))
            .thenReturn(listOf(TableFeedItem(UUID.randomUUID(), source.id, null, "https://example.com/seen")))

        feedCrawlService.collectAndDispatch()

        verifyNoInteractions(feedJobDispatcher)
    }

    @Test
    fun `신규 후보는 소스당 1건만 취해 dispatch한다`() {
        val source =
            TableFeedSource(id = UUID.randomUUID(), name = "GeekNews", url = "https://news.hada.io/rss/news")
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot())
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(listOf(source))
        // 소스당 최대 1건만 취한다 - 6개를 내려도 최종 후보는 1건이어야 한다.
        `when`(feedParser.fetch(source.url)).thenReturn(
            (1..6).map { FeedEntry("글 $it", "https://example.com/$it") },
        )
        `when`(feedItemRepository.findAllByNormalizedUrlIn(anyCollection())).thenReturn(emptyList())

        feedCrawlService.collectAndDispatch()

        // dispatch(event: FeedItemJobEvent)는 Kotlin이 선언한 non-null 파라미터라, ArgumentCaptor나
        // eq()가 내부적으로 돌려주는 합성 null 값을 그대로 넘기면 Kotlin의 호출부 null 체크에 걸려
        // NPE가 난다 - 값을 정확히 알고 있으므로 매처 대신 실제 값으로 직접 비교한다.
        val expectedItems = listOf(FeedCrawlItem(source.id, "글 1", "https://example.com/1"))
        verify(feedJobDispatcher).dispatch(FeedItemJobEvent(botId, expectedItems))
    }

    @Test
    fun `전체 후보가 상한을 넘으면 5건으로 잘라 dispatch한다`() {
        // 소스 6개가 각각 신규 1건씩 내놓으면 전체 후보는 6건이지만, MAX_ITEMS_TOTAL(5)에서 잘려야 한다.
        // 소스 순회 순서는 collectAndDispatch 내부에서 매번 셔플되므로 어느 소스가 잘리는지는
        // 검증하지 않고 dispatch된 건수만 확인한다.
        val sources =
            (1..6).map {
                TableFeedSource(id = UUID.randomUUID(), name = "source$it", url = "https://example.com/feed/$it")
            }
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot())
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(sources)
        sources.forEach { source ->
            `when`(feedParser.fetch(source.url))
                .thenReturn(listOf(FeedEntry("글", "https://example.com/${source.id}")))
        }
        `when`(feedItemRepository.findAllByNormalizedUrlIn(anyCollection())).thenReturn(emptyList())

        feedCrawlService.collectAndDispatch()

        // 소스 순서가 셔플되어 어떤 5건이 뽑힐지 알 수 없으므로, non-null 파라미터를 요구하는
        // dispatch()에 captor.capture()/any()를 직접 넘기면 90~93행과 같은 이유로 NPE가 난다.
        // 실제 호출 기록을 그대로 조회해 우회한다.
        val invocation = mockingDetails(feedJobDispatcher).invocations.single()
        val dispatchedEvent = invocation.arguments[0] as FeedItemJobEvent
        assertEquals(5, dispatchedEvent.items.size)
    }

    @Test
    fun `피드 본문을 FeedCrawlItem에 그대로 실어 dispatch한다`() {
        val source =
            TableFeedSource(id = UUID.randomUUID(), name = "당근 기술블로그", url = "https://medium.com/feed/daangn")
        `when`(memberRepository.findFirstByIsBotTrue()).thenReturn(bot())
        `when`(feedSourceRepository.findAllByEnabledTrue()).thenReturn(listOf(source))
        `when`(feedParser.fetch(source.url))
            .thenReturn(listOf(FeedEntry("본문 있는 글", "https://example.com/1", "본문 내용")))
        `when`(feedItemRepository.findAllByNormalizedUrlIn(anyCollection())).thenReturn(emptyList())

        feedCrawlService.collectAndDispatch()

        val expectedItems = listOf(FeedCrawlItem(source.id, "본문 있는 글", "https://example.com/1", "본문 내용"))
        verify(feedJobDispatcher).dispatch(FeedItemJobEvent(botId, expectedItems))
    }

    @Test
    fun `Stage B에서 한 항목이 실패해도 나머지 항목은 계속 처리한다`() {
        val ok1 = FeedCrawlItem(UUID.randomUUID(), "성공1", "https://example.com/1")
        val fail = FeedCrawlItem(UUID.randomUUID(), "실패", "https://example.com/2")
        val ok2 = FeedCrawlItem(UUID.randomUUID(), "성공2", "https://example.com/3")
        doThrow(RuntimeException("boom"))
            .`when`(feedItemProcessor).processFeedItem(botId, fail)

        feedCrawlService.processFeedItemJob(FeedItemJobEvent(botId, listOf(ok1, fail, ok2)))

        verify(feedItemProcessor, times(1)).processFeedItem(botId, ok1)
        verify(feedItemProcessor, times(1)).processFeedItem(botId, fail)
        verify(feedItemProcessor, times(1)).processFeedItem(botId, ok2)
    }
}
