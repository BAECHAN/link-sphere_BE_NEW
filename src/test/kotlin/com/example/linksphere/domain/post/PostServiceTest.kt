package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.interaction.BookmarkId
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TargetType
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class PostServiceTest {

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var categoryRepository: CategoryRepository

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var bookmarkRepository: BookmarkRepository

    @Mock private lateinit var reactionRepository: ReactionRepository

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var eventPublisher: ApplicationEventPublisher

    @Mock private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @Mock private lateinit var safeUrlValidator: SafeUrlValidator

    @InjectMocks private lateinit var postService: PostService

    private fun privatePost(postId: UUID, ownerId: UUID) = TablePost(
        id = postId,
        userId = ownerId,
        url = "https://example.com",
        title = "제목",
        isPrivate = true,
    )

    @Test
    fun `getPostById returns post when owner views their own private post`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = privatePost(postId, ownerId)
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(ownerId)).thenReturn(Optional.of(owner))
        `when`(bookmarkRepository.findById(BookmarkId(ownerId, postId))).thenReturn(Optional.empty())
        lenient().`when`(reactionRepository.countByTargetIdAndTargetType(postId, TargetType.POST)).thenReturn(0L)
        lenient().`when`(reactionRepository.existsByTargetIdAndTargetTypeAndUserId(postId, TargetType.POST, ownerId))
            .thenReturn(false)
        lenient().`when`(bookmarkRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(commentRepository.countByPostId(postId)).thenReturn(0L)

        val result = postService.getPostById(postId, ownerId)

        assertEquals(postId, result.id)
    }

    @Test
    fun `getPostById throws PostNotFoundException when another user views a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = privatePost(postId, ownerId)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            postService.getPostById(postId, otherUserId)
        }
    }

    @Test
    fun `getPostById throws PostNotFoundException when anonymous user views a private post`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = privatePost(postId, ownerId)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            postService.getPostById(postId, null)
        }
    }
}
