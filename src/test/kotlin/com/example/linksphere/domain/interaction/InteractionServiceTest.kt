package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.TableComment
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.CommentNotFoundException
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
import org.mockito.Mockito.inOrder
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
    fun `setPostLike(true) 는 남의 비공개 글이면 PostNotFoundException`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.setPostLike(postId, otherUserId, liked = true)
        }
    }

    @Test
    fun `setPostLike 는 글이 없으면 PostNotFoundException`() {
        val postId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        `when`(postRepository.findById(postId)).thenReturn(Optional.empty())

        assertThrows(PostNotFoundException::class.java) {
            interactionService.setPostLike(postId, userId, liked = true)
        }
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

    // 예전에는 IllegalArgumentException의 공통 매핑으로 code가 NOT_FOUND였다 - 다른 댓글 경로와 같은 COMMENT_NOT_FOUND를 낸다
    @Test
    fun `setCommentLike 는 댓글이 없으면 CommentNotFoundException`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.empty())

        assertThrows(CommentNotFoundException::class.java) {
            interactionService.setCommentLike(commentId, userId, liked = true)
        }
        verify(commentReactionRepository, never()).insertIgnoreConflict(userId, commentId)
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
    fun `setCommentLike 는 남의 비공개 글의 댓글이면 PostNotFoundException`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.setCommentLike(commentId, otherUserId, liked = true)
        }
    }

    @Test
    fun `setCommentLike 는 톰스톤 검사보다 비공개 글 검증을 먼저 한다`() {
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
            interactionService.setCommentLike(commentId, otherUserId, liked = true)
        }
    }

    @Test
    fun `setBookmark(true) 는 주인이면 자기 비공개 글도 저장한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        val result = interactionService.setBookmark(postId, ownerId, bookmarked = true)

        assertTrue(result.isBookmarked)
        verify(bookmarkRepository).insertIgnoreConflict(ownerId, postId)
    }

    @Test
    fun `setBookmark(true) 는 북마크만 보장하고 기존 폴더 소속은 건드리지 않은 채 서버의 folderIds 를 돌려준다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        // 다른 기기에서 이미 폴더에 저장해 둔 상태(낡은 화면에서 "저장"을 누른 경우)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(ownerId, postId)).thenReturn(listOf(folderId))

        val result = interactionService.setBookmark(postId, ownerId, bookmarked = true)

        assertTrue(result.isBookmarked)
        assertEquals(listOf(folderId), result.folderIds)
        verify(bookmarkRepository).insertIgnoreConflict(ownerId, postId)
        verify(bookmarkRepository, never()).deleteByUserIdAndPostId(ownerId, postId)
        verify(bookmarkFolderItemRepository, never()).deleteByUserIdAndPostId(ownerId, postId)
    }

    @Test
    fun `setBookmark(true) 는 남의 비공개 글이면 PostNotFoundException`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        assertThrows(PostNotFoundException::class.java) {
            interactionService.setBookmark(postId, otherUserId, bookmarked = true)
        }
        verify(bookmarkRepository, never()).insertIgnoreConflict(otherUserId, postId)
    }

    @Test
    fun `setBookmark(false) 는 공개 여부를 확인하지 않고 소속과 북마크만 지운다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()

        val result = interactionService.setBookmark(postId, userId, bookmarked = false)

        assertFalse(result.isBookmarked)
        assertTrue(result.folderIds.isEmpty())
        val order = inOrder(bookmarkFolderItemRepository, bookmarkRepository)
        order.verify(bookmarkFolderItemRepository).deleteByUserIdAndPostId(userId, postId)
        order.verify(bookmarkRepository).deleteByUserIdAndPostId(userId, postId)
        verify(bookmarkRepository, never()).insertIgnoreConflict(userId, postId)
        verify(postRepository, never()).findById(postId)
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
        verify(bookmarkRepository, never()).deleteIfNoFolderItems(userId, postId)
    }

    @Test
    fun `removeBookmarkFolder(removeBookmarkIfEmpty) 는 소속을 뺀 뒤 남은 소속이 없을 때만 북마크를 지운다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()

        `when`(bookmarkRepository.existsByUserIdAndPostId(userId, postId)).thenReturn(false)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(emptyList())

        val result = interactionService.removeBookmarkFolder(postId, folderId, userId, removeBookmarkIfEmpty = true)

        assertFalse(result.isBookmarked)
        assertTrue(result.folderIds.isEmpty())
        // 소속을 먼저 빼야 "남은 소속 없음"을 판단할 수 있다
        val order = inOrder(bookmarkFolderItemRepository, bookmarkRepository)
        order.verify(bookmarkFolderItemRepository).deleteByUserIdAndPostIdAndFolderId(userId, postId, folderId)
        order.verify(bookmarkRepository).deleteIfNoFolderItems(userId, postId)
    }

    @Test
    fun `removeBookmarkFolder(removeBookmarkIfEmpty) 는 다른 기기가 추가한 소속이 남아 있으면 북마크를 유지한 실제 상태를 돌려준다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val otherFolderId = UUID.randomUUID()

        // 남은 소속이 있어 조건부 삭제가 0행 — 북마크가 그대로 남는다
        `when`(bookmarkRepository.deleteIfNoFolderItems(userId, postId)).thenReturn(0)
        `when`(bookmarkRepository.existsByUserIdAndPostId(userId, postId)).thenReturn(true)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(listOf(otherFolderId))

        val result = interactionService.removeBookmarkFolder(postId, folderId, userId, removeBookmarkIfEmpty = true)

        assertTrue(result.isBookmarked)
        assertEquals(listOf(otherFolderId), result.folderIds)
        verify(bookmarkRepository, never()).deleteByUserIdAndPostId(userId, postId)
    }
}
