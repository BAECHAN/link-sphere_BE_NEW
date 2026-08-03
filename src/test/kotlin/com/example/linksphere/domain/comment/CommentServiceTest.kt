package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.interaction.TableCommentReaction
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CommentServiceTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var commentReactionRepository: CommentReactionRepository

    @Mock private lateinit var supabaseStorageService: SupabaseStorageService

    @Mock private lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks private lateinit var commentService: CommentService

    @Test
    fun `getComments throws PostNotFoundException when another user views comments of a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            commentService.getComments(postId, otherUserId)
        }
    }

    @Test
    fun `getComments throws PostNotFoundException when anonymous user views comments of a private post`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            commentService.getComments(postId, null)
        }
    }

    @Test
    fun `getComments throws PostNotFoundException when post does not exist`() {
        val postId = UUID.randomUUID()
        `when`(postRepository.findById(postId)).thenReturn(Optional.empty())

        assertThrows(PostNotFoundException::class.java) {
            commentService.getComments(postId, null)
        }
    }

    @Test
    fun `createComment throws PostNotFoundException when another user comments on a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            commentService.createComment(postId, otherUserId, "댓글 내용", null)
        }
    }

    @Test
    fun `createComment은 이미지 URL만 있는 댓글의 linkUrl을 null로 저장한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester")
        val imageUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/abc.png"

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        `when`(supabaseStorageService.isManagedUrl(imageUrl)).thenReturn(true)

        val captor = ArgumentCaptor.forClass(TableComment::class.java)
        `when`(commentRepository.save(captor.capture())).thenAnswer { captor.value }

        commentService.createComment(postId, userId, null, listOf(imageUrl))

        assertNull(captor.value.linkUrl)
    }

    @Test
    fun `createComment은 이미지 URL보다 먼저 등장해도 이미지는 건너뛰고 다음 URL을 linkUrl로 저장한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester")
        val imageUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/abc.png"
        val articleUrl = "https://example.com/article"

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        `when`(supabaseStorageService.isManagedUrl(imageUrl)).thenReturn(true)
        `when`(supabaseStorageService.isManagedUrl(articleUrl)).thenReturn(false)

        val captor = ArgumentCaptor.forClass(TableComment::class.java)
        `when`(commentRepository.save(captor.capture())).thenAnswer { captor.value }

        // 이미지 URL이 먼저 등장하지만(예: 첨부 이미지 뒤에 이어붙인 링크), isManagedUrl인 첫 URL은 건너뛴다.
        commentService.createComment(postId, userId, "$imageUrl 참고: $articleUrl", null)

        assertEquals(articleUrl, captor.value.linkUrl)
    }

    @Test
    fun `createReply throws PostNotFoundException when another user replies on a comment of a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val parentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)
        val parentComment = TableComment(id = parentId, postId = postId, userId = ownerId, content = "부모 댓글")

        `when`(commentRepository.findById(parentId)).thenReturn(Optional.of(parentComment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            commentService.createReply(parentId, otherUserId, "답글 내용", null)
        }
    }

    @Test
    fun `deleteComment 는 답글이 있으면 톰스톤 처리하고 좋아요를 삭제한다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val comment = TableComment(id = commentId, postId = postId, userId = userId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(true)
        `when`(commentRepository.save(comment)).thenReturn(comment)

        commentService.deleteComment(commentId, userId)

        verify(commentReactionRepository).deleteByCommentId(commentId)
        assertTrue(comment.isDeleted)
        assertEquals("삭제된 댓글입니다.", comment.content)
    }

    @Test
    fun `getComments 는 삭제된 댓글의 좋아요를 0, false 로 내보낸다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        // 삭제 시점에는 좋아요도 함께 삭제되지만, 삭제-좋아요 경쟁 상황의 잔여값이 남아 있는 경우를 가정한다.
        val tombstoneComment =
            TableComment(id = commentId, postId = postId, userId = ownerId, content = "삭제된 댓글입니다.", isDeleted = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)).thenReturn(listOf(tombstoneComment))
        `when`(commentReactionRepository.findAllByCommentIdIn(listOf(commentId)))
            .thenReturn(listOf(TableCommentReaction(ownerId, commentId)))
        `when`(commentReactionRepository.findAllByUserIdAndCommentIdIn(ownerId, listOf(commentId)))
            .thenReturn(listOf(TableCommentReaction(ownerId, commentId)))

        val result = commentService.getComments(postId, ownerId)

        assertEquals(0, result[0].likeCount)
        assertEquals(false, result[0].isLiked)
    }
}
