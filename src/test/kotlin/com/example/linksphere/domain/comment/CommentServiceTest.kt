package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.domain.post.UrlMetadataExtractor
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.PostNotFoundException
import com.example.linksphere.infra.fcm.FcmNotificationService
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CommentServiceTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var reactionRepository: ReactionRepository

    @Mock private lateinit var supabaseStorageService: SupabaseStorageService

    @Mock private lateinit var fcmNotificationService: FcmNotificationService

    @Mock private lateinit var urlMetadataExtractor: UrlMetadataExtractor

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
}
