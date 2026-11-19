package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberProfile
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostLinkParser
import com.example.linksphere.domain.post.PostLookupService
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.domain.post.VisiblePostTitle
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.CursorCodec
import com.example.linksphere.global.common.CursorKeyType
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.exception.CommentDeletedException
import com.example.linksphere.global.exception.CommentNotFoundException
import com.example.linksphere.global.exception.EmailNotVerifiedException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Limit
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Instant
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CommentServiceTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var memberService: MemberService

    @Mock private lateinit var commentReactionRepository: CommentReactionRepository

    @Mock private lateinit var supabaseStorageService: SupabaseStorageService

    @Mock private lateinit var eventPublisher: ApplicationEventPublisher

    @Mock private lateinit var advisoryLock: AdvisoryLock

    private lateinit var commentService: CommentService

    // 글은 실제 PostLookupService(post의 공개 조회)를 거쳐 읽는다 - 저장소만 mock이라 "비공개 글은 없는 글로 답한다"는
    // 판정까지 이 테스트가 그대로 본다.
    @BeforeEach
    fun setUp() {
        commentService =
            CommentService(
                commentRepository,
                PostLookupService(postRepository),
                PostLinkParser("https://linksphere.click"),
                memberService,
                commentReactionRepository,
                supabaseStorageService,
                eventPublisher,
                advisoryLock,
            )
    }

    private fun visibleTitle(postId: UUID, postTitle: String) = object : VisiblePostTitle {
        override val id = postId
        override val title = postTitle
    }

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
    fun `createComment은 탈퇴한(nickname이 null인) 회원이면 작성자 표시명을 '탈퇴한 사용자'로 대신한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val deletedMember = TableMember(id = userId, email = "deleted-$userId@deleted.invalid", password = "pw", nickname = null, emailVerified = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(deletedMember))
        val captor = ArgumentCaptor.forClass(TableComment::class.java)
        `when`(commentRepository.save(captor.capture())).thenAnswer { captor.value }

        val response = commentService.createComment(postId, userId, "댓글 내용", null)

        assertEquals("탈퇴한 사용자", response.author.nickname)
    }

    @Test
    fun `createComment은 탈퇴 유예 중(닉네임은 남아있지만 deletionRequestedAt이 있는) 회원도 작성자 표시명을 '탈퇴한 사용자'로 대신한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val pendingMember =
            TableMember(
                id = userId,
                email = "a@a.com",
                password = "pw",
                nickname = "tester",
                image = "https://example.com/a.png",
                emailVerified = true,
                deletionRequestedAt = Instant.now(),
            )

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(pendingMember))
        val captor = ArgumentCaptor.forClass(TableComment::class.java)
        `when`(commentRepository.save(captor.capture())).thenAnswer { captor.value }

        val response = commentService.createComment(postId, userId, "댓글 내용", null)

        assertEquals("탈퇴한 사용자", response.author.nickname)
        assertEquals(null, response.author.image)
    }

    @Test
    fun `createComment은 이메일 미인증 회원이면 EmailNotVerifiedException을 던지고 댓글을 저장하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        doThrow(EmailNotVerifiedException("Email verification required")).`when`(memberService).requireEmailVerified(userId, "create a comment")

        assertThrows(EmailNotVerifiedException::class.java) {
            commentService.createComment(postId, userId, "댓글 내용", null)
        }

        verifyNoInteractions(commentRepository)
    }

    @Test
    fun `createComment은 이미지 URL만 있는 댓글의 linkUrl을 null로 저장한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)
        val imageUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/abc.png"

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
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
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)
        val imageUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/abc.png"
        val articleUrl = "https://example.com/article"

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
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
    fun `createReply는 이메일 미인증 회원이면 EmailNotVerifiedException을 던지고 답글을 저장하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val parentId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val parentComment = TableComment(id = parentId, postId = postId, userId = userId, content = "부모 댓글")

        `when`(commentRepository.findById(parentId)).thenReturn(Optional.of(parentComment))
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        doThrow(EmailNotVerifiedException("Email verification required")).`when`(memberService).requireEmailVerified(userId, "create a reply")

        assertThrows(EmailNotVerifiedException::class.java) {
            commentService.createReply(parentId, userId, "답글 내용", null)
        }

        verify(commentRepository, never()).save(any())
    }

    @Test
    fun `deleteComment 는 답글이 있으면 톰스톤 처리하고 좋아요를 삭제한다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val createdLongAgo = LocalDateTime.of(2020, 1, 1, 0, 0)
        val comment = TableComment(id = commentId, postId = postId, userId = userId, content = "내용", updatedAt = createdLongAgo)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(true)
        `when`(commentRepository.save(comment)).thenReturn(comment)

        commentService.deleteComment(commentId, userId)

        verify(commentReactionRepository).deleteByCommentId(commentId)
        assertTrue(comment.isDeleted)
        assertEquals("삭제된 댓글입니다.", comment.content)
        assertTrue(comment.updatedAt.isAfter(createdLongAgo))
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
    fun `deleteComment throws ForbiddenException when the caller is not the author`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))

        assertThrows(ForbiddenException::class.java) {
            commentService.deleteComment(commentId, otherUserId)
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
    fun `deleteComment 는 다른 댓글 본문이 아직 쓰는 이미지를 지우지 않는다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (pastedUrl, ownUrl) = imageUrls(2)
        // pastedUrl은 남의 댓글 이미지를 붙여넣은 것 - 이 댓글을 지워도 원래 댓글이 계속 쓴다.
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "$pastedUrl\n$ownUrl")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(false)
        `when`(supabaseStorageService.isManagedUrl(pastedUrl)).thenReturn(true)
        `when`(supabaseStorageService.isManagedUrl(ownUrl)).thenReturn(true)
        `when`(commentRepository.existsOtherCommentContaining("%$pastedUrl%", commentId)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteComment(commentId, userId)
            TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<String>>
        verify(supabaseStorageService).deleteObjectsByPublicUrls(captureValue(captor))
        assertEquals(setOf(ownUrl), captor.value.toSet())
    }

    @Test
    fun `deleteComment 는 누군가 아바타로 쓰는 이미지를 지우지 않는다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (avatarUrl) = imageUrls(1)
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용\n\n$avatarUrl")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(false)
        `when`(supabaseStorageService.isManagedUrl(avatarUrl)).thenReturn(true)
        `when`(memberService.isImageInUse(avatarUrl)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteComment(commentId, userId)
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `deleteComment 는 참조 확인 패턴에서 LIKE 와일드카드를 이스케이프한다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val url = "https://xyz.supabase.co/storage/v1/object/public/comment_images/a%b!c.png"
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = url)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.existsByParentId(commentId)).thenReturn(false)
        `when`(supabaseStorageService.isManagedUrl(url)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.deleteComment(commentId, userId)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }

        verify(commentRepository).existsOtherCommentContaining(
            "%https://xyz.supabase.co/storage/v1/object/public/comment!_images/a!%b!!c.png%",
            commentId,
        )
    }

    @Test
    fun `deleteImagesForPost 는 다른 게시글 댓글이 쓰는 이미지를 지우지 않는다`() {
        val postId = UUID.randomUUID()
        val (sharedUrl, ownUrl) = imageUrls(2)

        `when`(commentRepository.findAllContentByPostId(postId)).thenReturn(listOf("$sharedUrl\n$ownUrl", ownUrl))
        `when`(supabaseStorageService.isManagedUrl(sharedUrl)).thenReturn(true)
        `when`(supabaseStorageService.isManagedUrl(ownUrl)).thenReturn(true)
        `when`(commentRepository.existsCommentOutsidePostContaining("%$sharedUrl%", postId)).thenReturn(true)

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
        assertEquals(listOf(ownUrl), captor.value.toList())
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
        `when`(commentReactionRepository.countByCommentIdIn(listOf(commentId)))
            .thenReturn(
                listOf(
                    object : CommentLikeCount {
                        override val commentId = commentId
                        override val count = 1L
                    },
                ),
            )
        `when`(commentReactionRepository.findAllByUserIdAndCommentIdIn(ownerId, listOf(commentId)))
            .thenReturn(listOf(TableCommentReaction(ownerId, commentId)))

        val result = commentService.getComments(postId, ownerId)

        assertEquals(0, result[0].likeCount)
        assertEquals(false, result[0].isLiked)
    }

    private fun imageUrls(count: Int): List<String> = (1..count).map {
        "https://xyz.supabase.co/storage/v1/object/public/comments/img$it.png"
    }

    // "가"는 UTF-8 3바이트 - 상한(6,000)이 3의 배수라 경계를 정확히 만들 수 있다.
    private fun contentOfBytes(bytes: Int): String = "가".repeat(bytes / 3)

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
    fun `createComment throws InvalidInputException when content exceeds the byte limit`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.createComment(UUID.randomUUID(), UUID.randomUUID(), contentOfBytes(6_003), null)
        }
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `createReply throws InvalidInputException when content exceeds the byte limit`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.createReply(UUID.randomUUID(), UUID.randomUUID(), contentOfBytes(6_003), null)
        }
        verifyNoInteractions(commentRepository)
    }

    @Test
    fun `updateComment throws InvalidInputException when content exceeds the byte limit`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.updateComment(UUID.randomUUID(), UUID.randomUUID(), contentOfBytes(6_003), null)
        }
        verifyNoInteractions(commentRepository)
    }

    // 본문·이미지가 둘 다 비면 입력 오류(400 INVALID_INPUT)다 - 예전에는 IllegalArgumentException의 공통 매핑으로
    // 404 NOT_FOUND가 나갔다(docs/plans/2026-10-25-be-architecture-audit.md PR3).
    @Test
    fun `createComment는 본문과 이미지가 모두 비면 InvalidInputException을 던진다`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.createComment(UUID.randomUUID(), UUID.randomUUID(), " ", emptyList())
        }
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `createReply는 본문과 이미지가 모두 비면 InvalidInputException을 던진다`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.createReply(UUID.randomUUID(), UUID.randomUUID(), null, null)
        }
        verifyNoInteractions(commentRepository)
    }

    @Test
    fun `updateComment는 본문과 이미지가 모두 비면 InvalidInputException을 던진다`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.updateComment(UUID.randomUUID(), UUID.randomUUID(), "", null)
        }
        verifyNoInteractions(commentRepository)
    }

    @Test
    fun `createReply는 답글에 다시 답글을 달면 InvalidInputException을 던진다`() {
        val replyId = UUID.randomUUID()
        val reply = TableComment(id = replyId, postId = UUID.randomUUID(), userId = UUID.randomUUID(), parentId = UUID.randomUUID(), content = "답글")
        `when`(commentRepository.findById(replyId)).thenReturn(Optional.of(reply))

        assertThrows(InvalidInputException::class.java) {
            commentService.createReply(replyId, UUID.randomUUID(), "답글의 답글", null)
        }
        verifyNoInteractions(postRepository)
    }

    @Test
    fun `updateComment 는 이미 삭제된 댓글이면 CommentDeletedException(409)을 던진다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "삭제된 댓글입니다.")
        comment.isDeleted = true

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))

        assertThrows(CommentDeletedException::class.java) {
            commentService.updateComment(commentId, userId, "수정 내용", null)
        }
    }

    @Test
    fun `createReply 는 부모 댓글이 없으면 CommentNotFoundException을 던진다`() {
        val parentId = UUID.randomUUID()
        `when`(commentRepository.findById(parentId)).thenReturn(Optional.empty())

        assertThrows(CommentNotFoundException::class.java) {
            commentService.createReply(parentId, UUID.randomUUID(), "답글 내용", null)
        }
    }

    @Test
    fun `updateComment 는 댓글이 없으면 CommentNotFoundException을 던진다`() {
        val commentId = UUID.randomUUID()
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.empty())

        assertThrows(CommentNotFoundException::class.java) {
            commentService.updateComment(commentId, UUID.randomUUID(), "수정 내용", null)
        }
    }

    @Test
    fun `deleteComment 는 댓글이 없으면 CommentNotFoundException을 던진다`() {
        val commentId = UUID.randomUUID()
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.empty())

        assertThrows(CommentNotFoundException::class.java) {
            commentService.deleteComment(commentId, UUID.randomUUID())
        }
    }

    @Test
    fun `updateComment throws ForbiddenException when the caller is not the author`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val comment = TableComment(id = commentId, postId = postId, userId = ownerId, content = "내용")

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))

        assertThrows(ForbiddenException::class.java) {
            commentService.updateComment(commentId, otherUserId, "수정 내용", null)
        }
    }

    @Test
    fun `createComment accepts exactly the maximum allowed images`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
        `when`(commentRepository.save(any(TableComment::class.java)))
            .thenAnswer { it.arguments[0] }

        // 예외 없이 끝까지 진행되면 5장은 통과한다는 뜻이다.
        commentService.createComment(postId, userId, "내용", imageUrls(5))
    }

    @Test
    fun `createComment accepts content at exactly the maximum byte size`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = userId, url = "https://example.com", title = "제목", isPrivate = false)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
        `when`(commentRepository.save(any(TableComment::class.java)))
            .thenAnswer { it.arguments[0] }

        // 예외 없이 끝까지 진행되면 경계값(6,000바이트)은 통과한다는 뜻이다.
        commentService.createComment(postId, userId, contentOfBytes(6_000), null)
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
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
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
    fun `updateComment 는 본문에서 뺀 이미지라도 다른 댓글이 아직 쓰면 지우지 않는다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (pastedUrl) = imageUrls(1)
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용\n\n$pastedUrl")
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
        `when`(supabaseStorageService.isManagedUrl(pastedUrl)).thenReturn(true)
        `when`(commentRepository.existsOtherCommentContaining("%$pastedUrl%", commentId)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.updateComment(commentId, userId, "내용", null)
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `updateComment preserves an image URL the user typed directly into the text`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (typedUrl) = imageUrls(1)
        // typedUrl은 첨부가 아니라 본문에 직접 타이핑된 URL이라고 가정 - images 파라미터에도
        // 다시 넘겨 새 content에 그대로 남긴다.
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용\n\n$typedUrl")
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
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

    // updatedAt이 val이던 동안은 수정해도 생성 시각에 고정돼 있었다(docs/plans/2026-10-25-be-architecture-audit.md PR5)
    @Test
    fun `updateComment 는 updatedAt 을 갱신한다`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val createdLongAgo = LocalDateTime.of(2020, 1, 1, 0, 0)
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용", updatedAt = createdLongAgo)
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))

        commentService.updateComment(commentId, userId, "고친 내용", null)

        assertTrue(comment.updatedAt.isAfter(createdLongAgo))
    }

    @Test
    fun `updateComment does not schedule any deletion when the image set is unchanged`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        val (url) = imageUrls(1)
        val comment = TableComment(id = commentId, postId = UUID.randomUUID(), userId = userId, content = "내용\n\n$url")
        val member = TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = true)

        `when`(commentRepository.findById(commentId)).thenReturn(Optional.of(comment))
        `when`(commentRepository.save(comment)).thenReturn(comment)
        `when`(memberService.getProfile(userId)).thenReturn(MemberProfile.from(member))
        `when`(supabaseStorageService.isManagedUrl(url)).thenReturn(true)

        TransactionSynchronizationManager.initSynchronization()
        try {
            commentService.updateComment(commentId, userId, "내용", listOf(url))
            assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    // 톰스톤 제외는 CommentRepository.findMyCommentsFirst/After의 JPQL이, 볼 수 없는 글 제외는
    // PostRepository.findVisibleTitles의 JPQL이 담당해 Mockito로는 검증할 수 없다(실제 Postgres로는
    // CommentRepositoryIntegrationTest·MyCommentIntegrationTest가 본다). 여기서는 Service가 읽은 행에 제목을 붙이고,
    // 제목이 오지 않은(볼 수 없는) 글의 댓글을 빼고 다시 채우는지를 검증한다.
    @Test
    fun `getMyCommentsByCursor maps comments into MyCommentPageResponse with post titles`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val comment = TableComment(postId = postId, userId = userId, content = "감사합니다")

        `when`(commentRepository.findMyCommentsFirst(userId, Limit.of(11))).thenReturn(listOf(comment))
        `when`(postRepository.findVisibleTitles(listOf(postId), userId)).thenReturn(listOf(visibleTitle(postId, "타입스크립트 5.9 정리")))

        val result = commentService.getMyCommentsByCursor(userId, null, 10)

        assertEquals(1, result.content.size)
        assertEquals(postId, result.content[0].postId)
        assertEquals("타입스크립트 5.9 정리", result.content[0].postTitle)
        assertEquals("감사합니다", result.content[0].content)
        assertNull(result.nextCursor)
    }

    @Test
    fun `getMyCommentsByCursor reads one extra row and returns a cursor only when more remain`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val createdAt = LocalDateTime.of(2026, 11, 5, 12, 0, 0, 123_456_000)
        val rows = List(3) { TableComment(postId = postId, userId = userId, content = "댓글 $it", createdAt = createdAt.minusSeconds(it.toLong())) }
        val keyTypes = listOf(CursorKeyType.TIME, CursorKeyType.ID)

        `when`(postRepository.findVisibleTitles(listOf(postId), userId)).thenReturn(listOf(visibleTitle(postId, "제목")))
        `when`(commentRepository.findMyCommentsFirst(userId, Limit.of(3))).thenReturn(rows)
        val first = commentService.getMyCommentsByCursor(userId, null, 2)

        assertEquals(listOf("댓글 0", "댓글 1"), first.content.map { it.content })
        assertEquals("제목", first.content[0].postTitle)
        assertEquals(listOf<Comparable<*>>(rows[1].createdAt, rows[1].id), CursorCodec.decode(first.nextCursor!!, "LATEST", keyTypes).keys)

        `when`(commentRepository.findMyCommentsAfter(userId, rows[1].createdAt, rows[1].id, Limit.of(3))).thenReturn(rows.drop(2))
        val second = commentService.getMyCommentsByCursor(userId, first.nextCursor, 2)

        assertEquals(listOf("댓글 2"), second.content.map { it.content })
        assertNull(second.nextCursor)
    }

    @Test
    fun `getMyCommentsByCursor rejects a cursor it cannot read`() {
        assertThrows(InvalidInputException::class.java) {
            commentService.getMyCommentsByCursor(UUID.randomUUID(), "not-a-cursor", 10)
        }
    }

    // 예전에는 글 연관관계가 비어 있으면 제목을 ""로 채웠다. 지금은 제목을 post에 묻고, post가 돌려주지 않은 글(볼 수 없게 된
    // 글)의 댓글은 목록에서 뺀다.
    @Test
    fun `getMyCommentsByCursor leaves out comments whose post is not visible`() {
        val userId = UUID.randomUUID()
        val visiblePostId = UUID.randomUUID()
        val hiddenPostId = UUID.randomUUID()
        val createdAt = LocalDateTime.of(2026, 11, 5, 12, 0)
        val rows =
            listOf(
                TableComment(postId = hiddenPostId, userId = userId, content = "가려진 글의 댓글", createdAt = createdAt),
                TableComment(postId = visiblePostId, userId = userId, content = "보이는 글의 댓글", createdAt = createdAt.minusSeconds(1)),
            )

        `when`(commentRepository.findMyCommentsFirst(userId, Limit.of(11))).thenReturn(rows)
        `when`(postRepository.findVisibleTitles(listOf(hiddenPostId, visiblePostId), userId)).thenReturn(listOf(visibleTitle(visiblePostId, "제목")))

        val result = commentService.getMyCommentsByCursor(userId, null, 10)

        assertEquals(listOf("보이는 글의 댓글"), result.content.map { it.content })
        assertNull(result.nextCursor)
    }

    @Test
    fun `getMyCommentsByCursor reads again to fill the page when comments were left out`() {
        val userId = UUID.randomUUID()
        val visiblePostId = UUID.randomUUID()
        val hiddenPostId = UUID.randomUUID()
        val createdAt = LocalDateTime.of(2026, 11, 5, 12, 0)
        val keyTypes = listOf(CursorKeyType.TIME, CursorKeyType.ID)
        // 첫 읽기 3개 가운데 가운데 댓글이 가려진 글의 것이다 - 2개짜리 쪽이 차지 않아 그 뒤를 다시 읽는다
        val firstRead =
            listOf(
                TableComment(postId = visiblePostId, userId = userId, content = "댓글 0", createdAt = createdAt),
                TableComment(postId = hiddenPostId, userId = userId, content = "가려짐", createdAt = createdAt.minusSeconds(1)),
                TableComment(postId = visiblePostId, userId = userId, content = "댓글 1", createdAt = createdAt.minusSeconds(2)),
            )
        val secondRead =
            listOf(
                TableComment(postId = visiblePostId, userId = userId, content = "댓글 2", createdAt = createdAt.minusSeconds(3)),
            )

        `when`(commentRepository.findMyCommentsFirst(userId, Limit.of(3))).thenReturn(firstRead)
        // 다시 읽을 때는 쪽 크기 + 1이 아니라 한 번에 51행을 달라고 한다
        `when`(commentRepository.findMyCommentsAfter(userId, firstRead[2].createdAt, firstRead[2].id, Limit.of(51))).thenReturn(secondRead)
        `when`(postRepository.findVisibleTitles(listOf(visiblePostId, hiddenPostId), userId)).thenReturn(listOf(visibleTitle(visiblePostId, "제목")))
        `when`(postRepository.findVisibleTitles(listOf(visiblePostId), userId)).thenReturn(listOf(visibleTitle(visiblePostId, "제목")))

        val result = commentService.getMyCommentsByCursor(userId, null, 2)

        // 쪽은 보이는 댓글 2개로 차고, 다시 읽은 "댓글 2"가 다음 쪽이 있음을 알려 준다. cursor는 쪽의 마지막 댓글이다
        assertEquals(listOf("댓글 0", "댓글 1"), result.content.map { it.content })
        assertEquals(listOf<Comparable<*>>(firstRead[2].createdAt, firstRead[2].id), CursorCodec.decode(result.nextCursor!!, "LATEST", keyTypes).keys)
    }

    @Test
    fun `getMyCommentsByCursor ends the page without a cursor when the comments after the left-out ones run out`() {
        val userId = UUID.randomUUID()
        val visiblePostId = UUID.randomUUID()
        val hiddenPostId = UUID.randomUUID()
        val createdAt = LocalDateTime.of(2026, 11, 5, 12, 0)
        val firstRead =
            listOf(
                TableComment(postId = visiblePostId, userId = userId, content = "댓글 0", createdAt = createdAt),
                TableComment(postId = hiddenPostId, userId = userId, content = "가려짐", createdAt = createdAt.minusSeconds(1)),
                TableComment(postId = hiddenPostId, userId = userId, content = "가려짐", createdAt = createdAt.minusSeconds(2)),
            )

        `when`(commentRepository.findMyCommentsFirst(userId, Limit.of(3))).thenReturn(firstRead)
        `when`(commentRepository.findMyCommentsAfter(userId, firstRead[2].createdAt, firstRead[2].id, Limit.of(51))).thenReturn(emptyList())
        `when`(postRepository.findVisibleTitles(listOf(visiblePostId, hiddenPostId), userId)).thenReturn(listOf(visibleTitle(visiblePostId, "제목")))

        val result = commentService.getMyCommentsByCursor(userId, null, 2)

        assertEquals(listOf("댓글 0"), result.content.map { it.content })
        assertNull(result.nextCursor)
    }

    // 다시 읽는 횟수에 상한이 없다 - 상한에서 멈춰 "빈 쪽 + 다음 cursor"를 내면 FE는 댓글이 0개라 다음 쪽을 부르지 않는다.
    // 가려진 글의 댓글이 여러 번 이어져도 보이는 댓글이 나오거나 댓글이 끝날 때까지 읽는다. 다시 읽을 때는 한 번에 51행씩 읽어
    // 가려진 댓글 54개를 넘기는 데 DB를 세 번만 오간다(쪽 크기 + 1씩 읽으면 19번이다).
    @Test
    fun `getMyCommentsByCursor keeps reading past many left-out comments until it finds a visible one`() {
        val userId = UUID.randomUUID()
        val visiblePostId = UUID.randomUUID()
        val hiddenPostId = UUID.randomUUID()
        val createdAt = LocalDateTime.of(2026, 11, 5, 12, 0)
        val firstRead = List(3) { TableComment(postId = hiddenPostId, userId = userId, content = "가려짐", createdAt = createdAt.minusSeconds(it.toLong())) }
        val refill = List(51) { TableComment(postId = hiddenPostId, userId = userId, content = "가려짐", createdAt = createdAt.minusSeconds((3 + it).toLong())) }
        val lastRead = listOf(TableComment(postId = visiblePostId, userId = userId, content = "보이는 댓글", createdAt = createdAt.minusSeconds(100)))

        `when`(commentRepository.findMyCommentsFirst(userId, Limit.of(3))).thenReturn(firstRead)
        `when`(commentRepository.findMyCommentsAfter(userId, firstRead.last().createdAt, firstRead.last().id, Limit.of(51))).thenReturn(refill)
        `when`(commentRepository.findMyCommentsAfter(userId, refill.last().createdAt, refill.last().id, Limit.of(51))).thenReturn(lastRead)
        `when`(postRepository.findVisibleTitles(listOf(hiddenPostId), userId)).thenReturn(emptyList())
        `when`(postRepository.findVisibleTitles(listOf(visiblePostId), userId)).thenReturn(listOf(visibleTitle(visiblePostId, "제목")))

        val result = commentService.getMyCommentsByCursor(userId, null, 2)

        assertEquals(listOf("보이는 댓글"), result.content.map { it.content })
        assertNull(result.nextCursor)
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

        assertTrue(commentService.setCommentLike(commentId, ownerId, liked = true))
        verify(commentReactionRepository).insertIgnoreConflict(ownerId, commentId)

        assertFalse(commentService.setCommentLike(commentId, ownerId, liked = false))
        verify(commentReactionRepository).deleteByUserIdAndCommentId(ownerId, commentId)
    }

    // 예전에는 IllegalArgumentException의 공통 매핑으로 code가 NOT_FOUND였다 - 다른 댓글 경로와 같은 COMMENT_NOT_FOUND를 낸다
    @Test
    fun `setCommentLike 는 댓글이 없으면 CommentNotFoundException`() {
        val userId = UUID.randomUUID()
        val commentId = UUID.randomUUID()
        `when`(commentRepository.findById(commentId)).thenReturn(Optional.empty())

        assertThrows(CommentNotFoundException::class.java) {
            commentService.setCommentLike(commentId, userId, liked = true)
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
            commentService.setCommentLike(commentId, ownerId, liked = true)
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
            commentService.setCommentLike(commentId, otherUserId, liked = true)
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
            commentService.setCommentLike(commentId, otherUserId, liked = true)
        }
    }

    // 작성자는 댓글과 조인하지 않고 id를 모아 member에 한 번에 묻는다. 탈퇴한 회원(닉네임 null)과 회원을 찾지 못한 댓글은
    // 같은 문구로 보인다
    @Test
    fun `getComments 는 작성자를 한 번에 묻고 탈퇴했거나 없는 작성자를 '탈퇴한 사용자'로 싣는다`() {
        val postId = UUID.randomUUID()
        val activeId = UUID.randomUUID()
        val withdrawnId = UUID.randomUUID()
        val missingId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = activeId, url = "https://example.com", title = "제목", isPrivate = false)
        val comments =
            listOf(
                TableComment(postId = postId, userId = activeId, content = "첫 댓글"),
                TableComment(postId = postId, userId = withdrawnId, content = "둘째 댓글"),
                TableComment(postId = postId, userId = activeId, content = "셋째 댓글"),
                TableComment(postId = postId, userId = missingId, content = "넷째 댓글"),
            )

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(commentRepository.findAllByPostIdOrderByCreatedAtAsc(postId)).thenReturn(comments)
        `when`(memberService.findProfiles(listOf(activeId, withdrawnId, activeId, missingId)))
            .thenReturn(
                mapOf(
                    activeId to MemberProfile(activeId, "tester", "https://example.com/a.png"),
                    withdrawnId to MemberProfile(withdrawnId, null, null),
                ),
            )

        val result = commentService.getComments(postId, null)

        assertEquals(listOf("tester", "탈퇴한 사용자", "tester", "탈퇴한 사용자"), result.map { it.author.nickname })
        assertEquals(listOf("https://example.com/a.png", null, "https://example.com/a.png", null), result.map { it.author.image })
        assertEquals(listOf(activeId, withdrawnId, activeId), result.take(3).map { it.author.id })
    }
}
