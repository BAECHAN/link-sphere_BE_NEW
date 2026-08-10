package com.example.linksphere.domain.comment

import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.interaction.TableCommentReaction
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.support.TransactionSynchronizationManager
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
    fun `deleteComment 는 첨부 이미지를 삭제하되 커밋 이후에만 스토리지에서 지운다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val (imageUrl) = imageUrls(1)
        val comment = TableComment(id = commentId, postId = postId, userId = userId, content = "내용\n\n$imageUrl")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(false)
        `when`(supabaseStorageService.isManagedUrl(imageUrl)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteComment(commentId, userId)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        verify(commentRepository).delete(comment)
        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<String>>
        verify(supabaseStorageService).deleteObjectsByPublicUrls(captureValue(captor))
        assertEquals(setOf(imageUrl), captor.value.toSet())
    }

    @Test
    fun `deleteComment 는 이미지가 없으면 정리 훅을 등록하지 않는다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val comment = TableComment(id = commentId, postId = postId, userId = userId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(false)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteComment(commentId, userId)
            // 정리할 이미지가 없으면 애초에 훅 자체를 등록하지 않는다 - 등록되지 않았다는 것 자체가
            // 삭제가 절대 일어나지 않는다는 증거다.
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `deleteImagesForPost 는 댓글 이미지를 커밋 이후에만 스토리지에서 지운다`() {
        val postId = UUID.randomUUID()
        val (imageUrl) = imageUrls(1)

        `when`(commentRepository.findAllContentByPostId(postId)).thenReturn(listOf("내용\n\n$imageUrl"))
        `when`(supabaseStorageService.isManagedUrl(imageUrl)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteImagesForPost(postId)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<String>>
        verify(supabaseStorageService).deleteObjectsByPublicUrls(captureValue(captor))
        assertEquals(setOf(imageUrl), captor.value.toSet())
    }

    @Test
    fun `deleteImagesForPost 는 이미지가 없으면 정리 훅을 등록하지 않는다`() {
        val postId = UUID.randomUUID()
        `when`(commentRepository.findAllContentByPostId(postId)).thenReturn(listOf("내용"))

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteImagesForPost(postId)
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
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

    private fun imageUrls(count: Int): List<String> = (1..count).map {
        "https://xyz.supabase.co/storage/v1/object/public/comments/img$it.png"
    }

    // Kotlin에서 captor.capture()를 non-null 파라미터(Collection<String>) 자리에 그대로 쓰면
    // 반환값이 실제로는 null이라 호출부에서 NullPointerException이 나고, Mockito의 매처 스택까지
    // 어긋나 이 클래스의 다른 테스트까지 연쇄로 깨진다. T를 제네릭으로 남겨야 `null as T`가
    // (타입 소거로) 체크되지 않는 캐스트가 된다 - 구체 타입으로 선언하면 다시 깨진다.
    private fun <T> captureValue(captor: ArgumentCaptor<T>): T {
        captor.capture()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    @Test
    fun `createComment throws InvalidInputException when images exceed the limit`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.createComment(UUID.randomUUID(), UUID.randomUUID(), "내용", imageUrls(6))
        }
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `createReply throws InvalidInputException when images exceed the limit`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.createReply(UUID.randomUUID(), UUID.randomUUID(), "내용", imageUrls(6))
        }
        verifyNoInteractions(commentRepository)
    }

    @Test
    fun `updateComment throws InvalidInputException when images exceed the limit`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.updateComment(UUID.randomUUID(), UUID.randomUUID(), "내용", imageUrls(6))
        }
        verifyNoInteractions(commentRepository)
    }

    @Test
    fun `createComment accepts exactly the maximum allowed images`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        `when`(commentRepository.save(any(TableComment::class.java)))
            .thenAnswer { it.arguments[0] }

        // 예외 없이 끝까지 진행되면 5장은 통과한다는 뜻이다.
        commentService.createComment(postId, userId, "내용", imageUrls(5))
    }

    @Test
    fun `updateComment deletes only the images removed from content, after commit`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (keptUrl, removedUrl1, removedUrl2) = imageUrls(3)
        val comment =
            TableComment(
                id = commentId,
                postId = UUID.randomUUID(),
                userId = userId,
                content = "내용\n\n$keptUrl\n$removedUrl1\n$removedUrl2",
            )
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        listOf(keptUrl, removedUrl1, removedUrl2).forEach {
            `when`(supabaseStorageService.isManagedUrl(it)).thenReturn(true)
        }

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.updateComment(commentId, userId, "내용", listOf(keptUrl))
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<String>>
        verify(supabaseStorageService).deleteObjectsByPublicUrls(captureValue(captor))
        assertEquals(setOf(removedUrl1, removedUrl2), captor.value.toSet())
    }

    @Test
    fun `updateComment preserves an image URL the user typed directly into the text`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (typedUrl) = imageUrls(1)
        // typedUrl은 첨부가 아니라 본문에 직접 타이핑된 URL이라고 가정 - images 파라미터에도
        // 다시 넘겨 새 content에 그대로 남긴다.
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용\n\n$typedUrl")
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        `when`(supabaseStorageService.isManagedUrl(typedUrl)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.updateComment(commentId, userId, "내용", listOf(typedUrl))
            // 제거된 이미지가 없으면 애초에 정리 훅 자체를 등록하지 않는다 - 등록되지 않았다는
            // 것 자체가 삭제가 절대 일어나지 않는다는 증거다.
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `updateComment does not schedule any deletion when the image set is unchanged`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (url) = imageUrls(1)
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용\n\n$url")
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        `when`(supabaseStorageService.isManagedUrl(url)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.updateComment(commentId, userId, "내용", listOf(url))
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
