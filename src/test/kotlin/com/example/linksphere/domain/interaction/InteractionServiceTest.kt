package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.TableComment
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class InteractionServiceTest {

    @Mock private lateinit var reactionRepository: ReactionRepository

    @Mock private lateinit var bookmarkRepository: BookmarkRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var commentRepository: CommentRepository

    @InjectMocks private lateinit var interactionService: InteractionService

    @Test
    fun `toggleLike on POST throws PostNotFoundException when another user likes a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.toggleLike(postId, TargetType.POST, otherUserId)
        }
    }

    @Test
    fun `toggleLike on POST throws PostNotFoundException when post does not exist`() {
        val postId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        `when`(postRepository.findById(postId)).thenReturn(Optional.empty())

        assertThrows(PostNotFoundException::class.java) {
            interactionService.toggleLike(postId, TargetType.POST, userId)
        }
    }

    @Test
    fun `toggleLike on COMMENT throws PostNotFoundException when another user likes a comment on a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.toggleLike(commentId, TargetType.COMMENT, otherUserId)
        }
    }

    @Test
    fun `toggleBookmark throws PostNotFoundException when another user bookmarks a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.toggleBookmark(postId, otherUserId)
        }
    }

    @Test
    fun `toggleBookmark succeeds when owner bookmarks their own private post`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(bookmarkRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)

        val result = interactionService.toggleBookmark(postId, ownerId)

        assertTrue(result)
    }
}
