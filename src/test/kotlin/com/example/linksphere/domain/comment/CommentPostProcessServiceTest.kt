package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.domain.post.UrlMetadata
import com.example.linksphere.domain.post.UrlMetadataExtractor
import com.example.linksphere.infra.aws.CommentJobDispatcher
import com.example.linksphere.infra.fcm.FcmNotificationService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CommentPostProcessServiceTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var fcmNotificationService: FcmNotificationService

    @Mock private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @Mock private lateinit var commentJobDispatcher: CommentJobDispatcher

    @InjectMocks private lateinit var commentPostProcessService: CommentPostProcessService

    @Test
    fun `processCommentJob은 알림 발송이 실패해도 링크 프리뷰 갱신은 계속한다`() {
        val postAuthorId = UUID.randomUUID()
        val commenterId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val articleUrl = "https://example.com/article"

        val post = TablePost(id = postId, userId = postAuthorId, url = "https://example.com", title = "제목")
        val commenter = TableMember(id = commenterId, email = "a@a.com", password = "pw", nickname = "tester")
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
        `when`(memberRepository.findById(commenterId)).thenReturn(Optional.of(commenter))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        doThrow(RuntimeException("Firebase 전송 계층 오류"))
            .`when`(fcmNotificationService)
            .sendCommentNotification(postAuthorId, "tester", "댓글 내용", postId, commentId)
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
}
