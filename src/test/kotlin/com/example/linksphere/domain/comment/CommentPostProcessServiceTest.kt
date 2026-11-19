package com.example.linksphere.domain.comment

import com.example.linksphere.domain.linkpreview.UrlMetadata
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.post.PostLinkParser
import com.example.linksphere.domain.post.PostLookupService
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.job.JobPublisher
import com.example.linksphere.infra.fcm.FcmNotificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CommentPostProcessServiceTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var fcmNotificationService: FcmNotificationService

    @Mock private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @Mock private lateinit var jobPublisher: JobPublisher

    private lateinit var commentPostProcessService: CommentPostProcessService

    // 글 작성자는 실제 PostLookupService(post의 공개 조회)를 거쳐 읽는다 - 저장소만 mock이다.
    @BeforeEach
    fun setUp() {
        commentPostProcessService =
            CommentPostProcessService(
                commentRepository,
                PostLookupService(postRepository),
                PostLinkParser("https://linksphere.click"),
                fcmNotificationService,
                urlMetadataExtractor,
                jobPublisher,
            )
    }

    @Test
    fun `processCommentJob은 알림 발송이 실패해도 링크 프리뷰 갱신은 계속한다`() {
        val postAuthorId = UUID.randomUUID()
        val commenterId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val articleUrl = "https://example.com/article"

        val post = TablePost(id = postId, userId = postAuthorId, url = "https://example.com", title = "제목")
        val comment =
            TableComment(
                id = commentId,
                postId = postId,
                userId = commenterId,
                content = "댓글 내용",
                linkUrl = articleUrl,
            )
        val meta = UrlMetadata(title = "Article", description = "desc", ogImage = "img", tags = emptyList(), pageContent = null)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        doThrow(RuntimeException("Firebase 전송 계층 오류"))
            .`when`(fcmNotificationService)
            .sendCommentNotification(postAuthorId, postId, commentId)
        `when`(urlMetadataExtractor.extract(articleUrl)).thenReturn(meta)
        `when`(commentRepository.save(comment)).thenReturn(comment)

        // 예외를 던지지 않고 정상 종료해야 한다 - FCM 전송 실패가 링크 프리뷰 갱신까지 막으면 안 된다.
        commentPostProcessService.processCommentJob(CommentPostProcessEvent(commentId = commentId, notify = true))

        assertEquals("Article", comment.linkTitle)
        assertEquals("desc", comment.linkDescription)
        assertEquals("img", comment.linkOgImage)
    }

    @Test
    fun `processCommentJob은 댓글이 이미 삭제됐으면 아무 것도 하지 않는다`() {
        val commentId = UUID.randomUUID()
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.empty())

        commentPostProcessService.processCommentJob(CommentPostProcessEvent(commentId = commentId, notify = true))

        verifyNoInteractions(fcmNotificationService, urlMetadataExtractor)
    }

    @Test
    fun `processCommentJob은 linkUrl이 없으면 크롤링을 하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val comment = TableComment(id = commentId, postId = postId, userId = userId, content = "링크 없는 댓글", linkUrl = null)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))

        commentPostProcessService.processCommentJob(CommentPostProcessEvent(commentId = commentId, notify = false))

        verifyNoInteractions(urlMetadataExtractor)
        assertNull(comment.linkTitle)
    }

    // 우리 글 주소는 어떤 글이든 같은 SPA 껍데기라 크롤링하면 제목이 "Link Sphere"로 저장된다. 링크 카드는 응답을 만들 때
    // 글에서 직접 읽으므로(CommentLinkedPostIntegrationTest) 여기서는 크롤링도 저장도 하지 않는다
    @Test
    fun `processCommentJob은 linkUrl이 우리 글 주소면 크롤링하지 않고 저장값도 채우지 않는다`() {
        val commentId = UUID.randomUUID()
        val comment =
            TableComment(
                id = commentId,
                postId = UUID.randomUUID(),
                userId = UUID.randomUUID(),
                content = "이 글 보세요",
                linkUrl = "https://linksphere.click/post/${UUID.randomUUID()}",
            )

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))

        commentPostProcessService.processCommentJob(CommentPostProcessEvent(commentId = commentId, notify = false))

        verifyNoInteractions(urlMetadataExtractor)
        verify(commentRepository, never()).save(comment)
        assertNull(comment.linkTitle)
    }
}
