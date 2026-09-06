package com.example.linksphere.domain.feed

import com.example.linksphere.domain.post.AiStatus
import com.example.linksphere.domain.post.PostCreateRequest
import com.example.linksphere.domain.post.PostResponse
import com.example.linksphere.domain.post.PostService
import com.example.linksphere.domain.post.PostStats
import com.example.linksphere.domain.post.PostUserInteractions
import com.example.linksphere.domain.post.UserSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.ArgumentMatchers.isNull
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class FeedItemProcessorTest {

    @Mock private lateinit var feedItemRepository: FeedItemRepository

    @Mock private lateinit var postService: PostService

    @InjectMocks private lateinit var feedItemProcessor: FeedItemProcessor

    private val botId = UUID.randomUUID()
    private val sourceId = UUID.randomUUID()
    private val item = FeedCrawlItem(sourceId, "제목", "https://example.com/a")

    // Kotlin에서 선언된 non-null 파라미터(UUID, PostCreateRequest) 자리에 ArgumentMatchers.any()의
    // 실제 반환값(null)을 그대로 쓰면 NPE가 난다 - PostServiceTest.anyUuid()와 동일한 우회.
    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    private fun dummyPostResponse(postId: UUID) = PostResponse(
        id = postId,
        userId = botId,
        url = item.url,
        title = item.title,
        description = null,
        tags = null,
        categories = emptyList(),
        ogImage = null,
        aiSummary = null,
        createdAt = null,
        aiStatus = AiStatus.NONE,
        isPrivate = false,
        stats = PostStats(0, 0, 0, 0),
        userInteractions = PostUserInteractions(false, false),
        author = UserSummary(botId, "링크봇", null),
    )

    @Test
    fun `claim 실패(이미 처리된 URL)면 게시글을 만들지 않는다`() {
        `when`(feedItemRepository.claim(anyNonNull(), eq(sourceId), isNull(), anyString())).thenReturn(0)

        feedItemProcessor.processFeedItem(botId, item)

        // createPost가 fallbackContent 파라미터를 얻으면서 2-인자에서 3-인자 호출로 바뀌었다 -
        // Kotlin 기본값 파라미터는 컴파일 시 별도의 $default 합성 브리지를 통해 호출되므로,
        // 여기서 인자 수를 실제 프로덕션 호출부(3-인자)와 맞추지 않으면 서로 다른 메서드를
        // 검증하게 되어 이 verify가 실제로는 아무것도 검증하지 못한다.
        verify(postService, never()).createPost(anyNonNull(), anyNonNull(), ArgumentMatchers.any())
        verify(feedItemRepository, never()).attachPost(anyNonNull(), anyNonNull())
    }

    @Test
    fun `claim 성공이면 봇 명의로 게시글을 만들고 원장에 postId를 붙인다`() {
        `when`(feedItemRepository.claim(anyNonNull(), eq(sourceId), isNull(), anyString())).thenReturn(1)
        val postId = UUID.randomUUID()
        // 어떤 요청이 만들어질지 미리 정확히 알 수 있으므로, 매처 대신 실제 값으로 직접 비교한다 -
        // eq()/ArgumentCaptor.capture()는 Kotlin의 non-null 파라미터 호출부 null 체크에 걸려
        // NPE가 나는 경우가 있다(위 anyNonNull()이 필요한 이유와 동일 계열의 함정).
        val expectedRequest = PostCreateRequest(url = item.url, title = item.title, isPrivate = false)
        // item.content가 null이므로 fallbackContent도 null로 넘어간다 - 3-인자로 명시한다(위 주석 참고).
        `when`(postService.createPost(botId, expectedRequest, null)).thenReturn(dummyPostResponse(postId))

        feedItemProcessor.processFeedItem(botId, item)

        verify(postService).createPost(botId, expectedRequest, null)

        // itemId(첫 인자)는 프로덕션 코드 안에서 무작위로 생성되어 미리 알 수 없다 - Mockito의
        // 매처 체계를 아예 거치지 않는 mockingDetails로 실제 호출 인자를 순수 리플렉션으로 읽는다.
        val attachInvocation =
            mockingDetails(feedItemRepository).invocations.single { it.method.name == "attachPost" }
        assertEquals(postId, attachInvocation.arguments[1])
    }

    @Test
    fun `RSS 본문이 있으면 fallbackContent로 넘긴다`() {
        val itemWithContent = FeedCrawlItem(sourceId, "제목", "https://example.com/a", "RSS 본문")
        `when`(feedItemRepository.claim(anyNonNull(), eq(sourceId), isNull(), anyString())).thenReturn(1)
        val postId = UUID.randomUUID()
        val expectedRequest = PostCreateRequest(url = itemWithContent.url, title = itemWithContent.title, isPrivate = false)
        `when`(postService.createPost(botId, expectedRequest, "RSS 본문")).thenReturn(dummyPostResponse(postId))

        feedItemProcessor.processFeedItem(botId, itemWithContent)

        verify(postService).createPost(botId, expectedRequest, "RSS 본문")
    }
}
