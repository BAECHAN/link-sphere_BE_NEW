package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.ReactionRepository
import com.example.linksphere.domain.interaction.TableBookmarkFolderItem
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
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
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

    @Mock private lateinit var bookmarkFolderItemRepository: BookmarkFolderItemRepository

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
        `when`(bookmarkRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
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

    @Test
    fun `stats bookmarkCount 는 소속 폴더 수가 아니라 북마크 row 수다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(ownerId)).thenReturn(Optional.of(owner))
        // countByPostId(=2) 와 실제 소속 폴더 개수(=3)를 의도적으로 다르게 둔다 —
        // stats.bookmarkCount 가 소속 수가 아니라 북마크 row 수를 세는지 확인하기 위함.
        `when`(bookmarkRepository.countByPostId(postId)).thenReturn(2L)
        `when`(bookmarkRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(true)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(ownerId, postId))
            .thenReturn(listOf(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
        lenient().`when`(reactionRepository.countByTargetIdAndTargetType(postId, TargetType.POST)).thenReturn(0L)
        lenient().`when`(reactionRepository.existsByTargetIdAndTargetTypeAndUserId(postId, TargetType.POST, ownerId))
            .thenReturn(false)
        lenient().`when`(commentRepository.countByPostId(postId)).thenReturn(0L)

        val result = postService.getPostById(postId, ownerId)

        assertEquals(2, result.stats.bookmarkCount)
        assertEquals(3, result.userInteractions.bookmarkFolderIds.size)
    }

    @Test
    fun `buildResponsesFromPosts 는 소속 폴더를 한 번의 쿼리로 채운다`() {
        val userId = UUID.randomUUID()
        val postId1 = UUID.randomUUID()
        val postId2 = UUID.randomUUID()
        val folderId1 = UUID.randomUUID()
        val folderId2 = UUID.randomUUID()
        val post1 =
            TablePost(id = postId1, userId = userId, url = "https://example.com/1", title = "글1", isPrivate = false)
        val post2 =
            TablePost(id = postId2, userId = userId, url = "https://example.com/2", title = "글2", isPrivate = false)
        val member = TableMember(id = userId, email = "user@example.com", password = "enc", nickname = "user")

        `when`(memberRepository.findAllById(listOf(userId))).thenReturn(listOf(member))
        `when`(bookmarkRepository.findAllByPostIdIn(listOf(postId1, postId2))).thenReturn(emptyList())
        `when`(bookmarkRepository.findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2)))
            .thenReturn(emptyList())
        `when`(bookmarkFolderItemRepository.findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2)))
            .thenReturn(
                listOf(
                    TableBookmarkFolderItem(userId, postId1, folderId1),
                    TableBookmarkFolderItem(userId, postId1, folderId2),
                ),
            )
        `when`(reactionRepository.findAllByTargetIdInAndTargetType(listOf(postId1, postId2), TargetType.POST))
            .thenReturn(emptyList())
        `when`(
            reactionRepository.findAllByUserIdAndTargetIdInAndTargetType(
                userId,
                listOf(postId1, postId2),
                TargetType.POST,
            ),
        ).thenReturn(emptyList())
        `when`(commentRepository.countByPostIdIn(listOf(postId1, postId2))).thenReturn(emptyList())

        val result = postService.buildResponsesFromPosts(listOf(post1, post2), userId)

        val byId = result.associateBy { it.id }
        assertEquals(listOf(folderId1, folderId2), byId.getValue(postId1).userInteractions.bookmarkFolderIds)
        assertEquals(emptyList<UUID>(), byId.getValue(postId2).userInteractions.bookmarkFolderIds)
        verify(bookmarkFolderItemRepository, times(1))
            .findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2))
    }
}
