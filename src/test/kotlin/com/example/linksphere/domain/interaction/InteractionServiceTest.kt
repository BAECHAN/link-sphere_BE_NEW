package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.TableComment
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class InteractionServiceTest {

    @Mock private lateinit var postReactionRepository: PostReactionRepository

    @Mock private lateinit var commentReactionRepository: CommentReactionRepository

    @Mock private lateinit var bookmarkRepository: BookmarkRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

    @Mock private lateinit var bookmarkFolderItemRepository: BookmarkFolderItemRepository

    @InjectMocks private lateinit var interactionService: InteractionService

    @Test
    fun `togglePostLike throws PostNotFoundException when another user likes a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.togglePostLike(postId, otherUserId)
        }
    }

    @Test
    fun `togglePostLike throws PostNotFoundException when post does not exist`() {
        val postId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        `when`(postRepository.findById(postId)).thenReturn(Optional.empty())

        assertThrows(PostNotFoundException::class.java) {
            interactionService.togglePostLike(postId, userId)
        }
    }

    @Test
    fun `togglePostLike 는 좋아요 추가 시 isLiked=true 와 likeCount 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(postReactionRepository.deleteByUserIdAndPostId(ownerId, postId)).thenReturn(0)
        `when`(postReactionRepository.countByPostId(postId)).thenReturn(5L)

        val result = interactionService.togglePostLike(postId, ownerId)

        assertTrue(result.isLiked)
        assertEquals(5, result.likeCount)
        verify(postReactionRepository).insertIgnoreConflict(ownerId, postId)
        verify(postReactionRepository).countByPostId(postId)
    }

    // 동시 요청이 먼저 좋아요를 넣어 insert가 0행이어도(ON CONFLICT DO NOTHING) 예외 없이 좋아요 상태를 돌려준다
    @Test
    fun `togglePostLike 는 동시 요청이 먼저 좋아요를 넣어 insert 가 0행이어도 isLiked=true 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(postReactionRepository.deleteByUserIdAndPostId(ownerId, postId)).thenReturn(0)
        `when`(postReactionRepository.insertIgnoreConflict(ownerId, postId)).thenReturn(0)
        `when`(postReactionRepository.countByPostId(postId)).thenReturn(5L)

        val result = interactionService.togglePostLike(postId, ownerId)

        assertTrue(result.isLiked)
        assertEquals(5, result.likeCount)
    }

    @Test
    fun `togglePostLike 는 좋아요 취소 시 isLiked=false 와 감소된 likeCount 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(postReactionRepository.deleteByUserIdAndPostId(ownerId, postId)).thenReturn(1)
        `when`(postReactionRepository.countByPostId(postId)).thenReturn(3L)

        val result = interactionService.togglePostLike(postId, ownerId)

        assertFalse(result.isLiked)
        assertEquals(3, result.likeCount)
        verify(postReactionRepository, never()).insertIgnoreConflict(ownerId, postId)
        verify(postReactionRepository).countByPostId(postId)
    }

    @Test
    fun `setPostLike(true) 는 지우지 않고 insertIgnoreConflict 만 하며 isLiked=true 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(postReactionRepository.countByPostId(postId)).thenReturn(7L)

        val result = interactionService.setPostLike(postId, ownerId, liked = true)

        assertTrue(result.isLiked)
        assertEquals(7, result.likeCount)
        verify(postReactionRepository).insertIgnoreConflict(ownerId, postId)
        verify(postReactionRepository, never()).deleteByUserIdAndPostId(ownerId, postId)
    }

    @Test
    fun `setPostLike(false) 는 추가하지 않고 일괄 삭제만 하며 isLiked=false 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(postReactionRepository.countByPostId(postId)).thenReturn(6L)

        val result = interactionService.setPostLike(postId, ownerId, liked = false)

        assertFalse(result.isLiked)
        assertEquals(6, result.likeCount)
        verify(postReactionRepository).deleteByUserIdAndPostId(ownerId, postId)
        verify(postReactionRepository, never()).insertIgnoreConflict(ownerId, postId)
    }

    @Test
    fun `setPostLike(false) 도 남의 비공개 글이면 PostNotFoundException`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.setPostLike(postId, otherUserId, liked = false)
        }
        verify(postReactionRepository, never()).deleteByUserIdAndPostId(otherUserId, postId)
    }

    @Test
    fun `setCommentLike 는 liked 에 따라 추가 또는 일괄 삭제만 한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertTrue(interactionService.setCommentLike(commentId, ownerId, liked = true))
        verify(commentReactionRepository).insertIgnoreConflict(ownerId, commentId)

        assertFalse(interactionService.setCommentLike(commentId, ownerId, liked = false))
        verify(commentReactionRepository).deleteByUserIdAndCommentId(ownerId, commentId)
    }

    @Test
    fun `setCommentLike 는 삭제된 댓글이면 InvalidInputException`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val comment =
            TableComment(id = commentId, postId = postId, userId = ownerId, content = "삭제된 댓글입니다.", isDeleted = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(InvalidInputException::class.java) {
            interactionService.setCommentLike(commentId, ownerId, liked = true)
        }
        verify(commentReactionRepository, never()).insertIgnoreConflict(ownerId, commentId)
    }

    @Test
    fun `toggleCommentLike throws PostNotFoundException when another user likes a comment on a private post`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.toggleCommentLike(commentId, otherUserId)
        }
    }

    @Test
    fun `toggleCommentLike 는 삭제된 댓글이면 InvalidInputException`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val comment =
            TableComment(id = commentId, postId = postId, userId = ownerId, content = "삭제된 댓글입니다.", isDeleted = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(InvalidInputException::class.java) {
            interactionService.toggleCommentLike(commentId, ownerId)
        }
        verify(commentReactionRepository, never()).deleteByUserIdAndCommentId(ownerId, commentId)
        verify(commentReactionRepository, never()).insertIgnoreConflict(ownerId, commentId)
    }

    @Test
    fun `toggleCommentLike 는 지운 행이 없으면 좋아요를 추가하고 true 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(commentReactionRepository.deleteByUserIdAndCommentId(ownerId, commentId)).thenReturn(0)

        val result = interactionService.toggleCommentLike(commentId, ownerId)

        assertTrue(result)
        verify(commentReactionRepository).insertIgnoreConflict(ownerId, commentId)
    }

    @Test
    fun `toggleCommentLike 는 좋아요를 지웠으면 추가하지 않고 false 를 반환한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(commentReactionRepository.deleteByUserIdAndCommentId(ownerId, commentId)).thenReturn(1)

        val result = interactionService.toggleCommentLike(commentId, ownerId)

        assertFalse(result)
        verify(commentReactionRepository, never()).insertIgnoreConflict(ownerId, commentId)
    }

    @Test
    fun `toggleCommentLike 는 톰스톤 검사보다 비공개 글 검증을 먼저 한다`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)
        val comment =
            TableComment(id = commentId, postId = postId, userId = ownerId, content = "삭제된 댓글입니다.", isDeleted = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.toggleCommentLike(commentId, otherUserId)
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
        `when`(bookmarkRepository.deleteByUserIdAndPostId(ownerId, postId)).thenReturn(0)

        val result = interactionService.toggleBookmark(postId, ownerId)

        assertTrue(result)
        verify(bookmarkRepository).insertIgnoreConflict(ownerId, postId)
    }

    @Test
    fun `toggleBookmark 취소 시 폴더 소속을 모두 삭제한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(bookmarkRepository.deleteByUserIdAndPostId(ownerId, postId)).thenReturn(1)

        val result = interactionService.toggleBookmark(postId, ownerId)

        assertTrue(!result)
        verify(bookmarkFolderItemRepository).deleteByUserIdAndPostId(ownerId, postId)
        verify(bookmarkRepository).deleteByUserIdAndPostId(ownerId, postId)
        verify(bookmarkRepository, never()).insertIgnoreConflict(ownerId, postId)
    }

    @Test
    fun `addBookmarkFolder 는 북마크가 없어도 북마크를 생성하고 폴더에 추가한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(listOf(folderId))

        val result = interactionService.addBookmarkFolder(postId, folderId, userId)

        assertTrue(result.isBookmarked)
        verify(bookmarkRepository).insertIgnoreConflict(userId, postId)
        verify(bookmarkFolderItemRepository).insertIgnoreConflict(userId, postId, folderId)
    }

    @Test
    fun `addBookmarkFolder 는 타인 폴더면 ForbiddenException`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val folder = TableBookmarkFolder(id = folderId, userId = otherUserId, name = "개발")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))

        assertThrows(ForbiddenException::class.java) {
            interactionService.addBookmarkFolder(postId, folderId, userId)
        }
    }

    @Test
    fun `addBookmarkFolder 는 없는 폴더면 BookmarkFolderNotFoundException`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.empty())

        assertThrows(BookmarkFolderNotFoundException::class.java) {
            interactionService.addBookmarkFolder(postId, folderId, userId)
        }
    }

    @Test
    fun `addBookmarkFolder 는 남의 비공개 글이면 PostNotFoundException`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.addBookmarkFolder(postId, folderId, otherUserId)
        }
        verify(bookmarkRepository, never()).insertIgnoreConflict(otherUserId, postId)
    }

    @Test
    fun `removeBookmarkFolder 는 마지막 폴더를 지워도 북마크는 삭제하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()

        `when`(bookmarkRepository.existsByUserIdAndPostId(userId, postId)).thenReturn(true)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(emptyList())

        val result = interactionService.removeBookmarkFolder(postId, folderId, userId)

        assertTrue(result.isBookmarked)
        assertTrue(result.folderIds.isEmpty())
        verify(bookmarkFolderItemRepository).deleteByUserIdAndPostIdAndFolderId(userId, postId, folderId)
        verify(bookmarkRepository, never()).deleteByUserIdAndPostId(userId, postId)
    }
}
