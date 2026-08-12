package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.interaction.TableBookmarkFolder
import com.example.linksphere.domain.interaction.TableBookmarkFolderItem
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
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

    @Mock private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

    @Mock private lateinit var postViewRepository: PostViewRepository

    @Mock private lateinit var postReactionRepository: PostReactionRepository

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var commentService: CommentService

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
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(postReactionRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
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
    fun `getPostById 는 로그인 사용자가 조회하면 post_views 를 upsert 한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(ownerId)).thenReturn(Optional.of(owner))
        `when`(bookmarkRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(postReactionRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
        lenient().`when`(bookmarkRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(commentRepository.countByPostId(postId)).thenReturn(0L)

        postService.getPostById(postId, ownerId)

        verify(postViewRepository).upsertView(ownerId, postId)
    }

    @Test
    fun `getPostById 는 비로그인 사용자가 조회하면 post_views 를 건드리지 않는다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)
        val owner = TableMember(id = ownerId, email = "owner@example.com", password = "enc", nickname = "owner")

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(memberRepository.findById(ownerId)).thenReturn(Optional.of(owner))
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(bookmarkRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(commentRepository.countByPostId(postId)).thenReturn(0L)

        postService.getPostById(postId, null)

        verifyNoInteractions(postViewRepository)
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
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(postReactionRepository.existsByUserIdAndPostId(ownerId, postId)).thenReturn(false)
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
        `when`(postReactionRepository.findAllByPostIdIn(listOf(postId1, postId2)))
            .thenReturn(emptyList())
        `when`(postReactionRepository.findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2)))
            .thenReturn(emptyList())
        `when`(commentRepository.countByPostIdIn(listOf(postId1, postId2))).thenReturn(emptyList())

        val result = postService.buildResponsesFromPosts(listOf(post1, post2), userId)

        val byId = result.associateBy { it.id }
        assertEquals(listOf(folderId1, folderId2), byId.getValue(postId1).userInteractions.bookmarkFolderIds)
        assertEquals(emptyList<UUID>(), byId.getValue(postId2).userInteractions.bookmarkFolderIds)
        verify(bookmarkFolderItemRepository, times(1))
            .findAllByUserIdAndPostIdIn(userId, listOf(postId1, postId2))
    }

    @Test
    fun `deletePost 는 댓글 이미지를 정리한 뒤 게시글을 삭제한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        postService.deletePost(postId, ownerId)

        verify(commentService).deleteImagesForPost(postId)
        verify(postRepository).delete(post)
    }

    // Kotlin에서 선언된 insertIgnoreConflict(userId: UUID, ...)는 파라미터가 non-null이라
    // ArgumentMatchers.any()의 실제 반환값(null)을 그대로 verify에 넘기면 NPE가 난다 —
    // OrphanImageCleanupRunnerTest.anyCollection()과 동일한 우회.
    private fun <T> anyUuid(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }

    // 크롤링을 항상 성공 취급(pageContent = null)해 AI 이벤트 발행 경로를 건드리지 않는다 —
    // 아래 등록+북마크 테스트들의 관심사가 아니므로 고정값으로 단순화.
    private fun stubMetadataExtraction(url: String) {
        `when`(urlMetadataExtractor.extract(url)).thenReturn(
            UrlMetadata(title = "제목", description = null, ogImage = null, tags = emptyList(), pageContent = null),
        )
    }

    // convertToResponse 가 요구하는 조회들을 채운다 — 값 자체는 각 테스트의 관심사가 아니다.
    private fun stubResponseBuild(userId: UUID, postId: UUID, isBookmarked: Boolean, bookmarkCount: Long) {
        val member = TableMember(id = userId, email = "user@example.com", password = "enc", nickname = "user")
        `when`(memberRepository.findById(userId)).thenReturn(Optional.of(member))
        `when`(bookmarkRepository.existsByUserIdAndPostId(userId, postId)).thenReturn(isBookmarked)
        lenient().`when`(postReactionRepository.countByPostId(postId)).thenReturn(0L)
        lenient().`when`(postReactionRepository.existsByUserIdAndPostId(userId, postId)).thenReturn(false)
        lenient().`when`(bookmarkRepository.countByPostId(postId)).thenReturn(bookmarkCount)
        lenient().`when`(commentRepository.countByPostId(postId)).thenReturn(0L)
    }

    @Test
    fun `folderIds 를 지정하지 않고 등록하면 북마크를 생성하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/no-bookmark"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        stubResponseBuild(userId, postId, isBookmarked = false, bookmarkCount = 0L)

        postService.createPost(userId, PostCreateRequest(url = url))

        verify(bookmarkRepository, never()).insertIgnoreConflict(anyUuid(), anyUuid())
        verify(bookmarkFolderItemRepository, never()).insertIgnoreConflict(anyUuid(), anyUuid(), anyUuid())
        verify(bookmarkFolderRepository, never()).findAllById(any())
    }

    @Test
    fun `folderIds 를 지정해 등록하면 북마크와 폴더 소속을 함께 생성한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId1 = UUID.randomUUID()
        val folderId2 = UUID.randomUUID()
        val url = "https://example.com/with-folders"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)
        val folder1 = TableBookmarkFolder(id = folderId1, userId = userId, name = "폴더1")
        val folder2 = TableBookmarkFolder(id = folderId2, userId = userId, name = "폴더2")

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        `when`(bookmarkFolderRepository.findAllById(listOf(folderId1, folderId2)))
            .thenReturn(listOf(folder1, folder2))
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(listOf(folderId1, folderId2))
        stubResponseBuild(userId, postId, isBookmarked = true, bookmarkCount = 1L)

        val result =
            postService.createPost(userId, PostCreateRequest(url = url, folderIds = listOf(folderId1, folderId2)))

        verify(postRepository).flush()
        verify(bookmarkRepository).insertIgnoreConflict(userId, postId)
        verify(bookmarkFolderItemRepository).insertIgnoreConflict(userId, postId, folderId1)
        verify(bookmarkFolderItemRepository).insertIgnoreConflict(userId, postId, folderId2)
        assertEquals(true, result.userInteractions.isBookmarked)
        assertEquals(listOf(folderId1, folderId2), result.userInteractions.bookmarkFolderIds)
    }

    @Test
    fun `bookmark 플래그만 true 면 폴더 소속 없이 미분류 북마크만 생성한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/uncategorized"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(emptyList())
        stubResponseBuild(userId, postId, isBookmarked = true, bookmarkCount = 1L)

        postService.createPost(userId, PostCreateRequest(url = url, bookmark = true))

        verify(bookmarkRepository).insertIgnoreConflict(userId, postId)
        verify(bookmarkFolderItemRepository, never()).insertIgnoreConflict(anyUuid(), anyUuid(), anyUuid())
        verify(bookmarkFolderRepository, never()).findAllById(any())
    }

    @Test
    fun `남의 폴더로 등록하면 ForbiddenException 이 발생하고 북마크가 생성되지 않는다`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val url = "https://example.com/forbidden-folder"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)
        val foreignFolder = TableBookmarkFolder(id = folderId, userId = otherUserId, name = "남의 폴더")

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        `when`(bookmarkFolderRepository.findAllById(listOf(folderId))).thenReturn(listOf(foreignFolder))

        assertThrows(ForbiddenException::class.java) {
            postService.createPost(userId, PostCreateRequest(url = url, folderIds = listOf(folderId)))
        }

        verify(bookmarkRepository, never()).insertIgnoreConflict(anyUuid(), anyUuid())
    }

    @Test
    fun `존재하지 않는 폴더로 등록하면 BookmarkFolderNotFoundException 이 발생한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val url = "https://example.com/missing-folder"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        `when`(bookmarkFolderRepository.findAllById(listOf(folderId))).thenReturn(emptyList())

        assertThrows(BookmarkFolderNotFoundException::class.java) {
            postService.createPost(userId, PostCreateRequest(url = url, folderIds = listOf(folderId)))
        }

        verify(bookmarkRepository, never()).insertIgnoreConflict(anyUuid(), anyUuid())
    }

    @Test
    fun `중복된 folderId 는 한 번만 소속시킨다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val url = "https://example.com/duplicate-folder"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "폴더")

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        `when`(bookmarkFolderRepository.findAllById(listOf(folderId))).thenReturn(listOf(folder))
        `when`(bookmarkFolderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
            .thenReturn(listOf(folderId))
        stubResponseBuild(userId, postId, isBookmarked = true, bookmarkCount = 1L)

        postService.createPost(userId, PostCreateRequest(url = url, folderIds = listOf(folderId, folderId)))

        verify(bookmarkFolderRepository).findAllById(listOf(folderId))
        verify(bookmarkFolderItemRepository, times(1)).insertIgnoreConflict(userId, postId, folderId)
    }
}
