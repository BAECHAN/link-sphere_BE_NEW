package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.TableBookmarkFolder
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.PostNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.InjectMocks
import org.mockito.Mock
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

    @Mock private lateinit var categoryService: CategoryService

    @Mock private lateinit var bookmarkRepository: BookmarkRepository

    @Mock private lateinit var bookmarkFolderItemRepository: BookmarkFolderItemRepository

    @Mock private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

    @Mock private lateinit var postViewRepository: PostViewRepository

    @Mock private lateinit var commentService: CommentService

    @Mock private lateinit var postResponseAssembler: PostResponseAssembler

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

    // convertToResponse는 이제 PostResponseAssembler 소관이라, PostService 테스트에서는
    // "무엇을 받아 그대로 반환하는지"만 확인하면 된다 - 값 자체를 만드는 로직(작성자·북마크
    // 집계 등)의 정확성은 PostResponseAssemblerTest가 검증한다.
    private fun stubAssemblerResponse(postId: UUID, isBookmarked: Boolean = false, bookmarkFolderIds: List<UUID> = emptyList()): PostResponse {
        val response = PostResponse(
            id = postId,
            url = "https://example.com",
            title = "제목",
            description = null,
            tags = emptyList(),
            categories = emptyList(),
            ogImage = null,
            aiSummary = null,
            createdAt = null,
            aiStatus = AiStatus.NONE,
            isPrivate = false,
            stats = PostStats(viewCount = 0, likeCount = 0, commentCount = 0, bookmarkCount = if (isBookmarked) 1 else 0),
            userInteractions = PostUserInteractions(isLiked = false, isBookmarked = isBookmarked, bookmarkFolderIds = bookmarkFolderIds),
            author = UserSummary(id = UUID.randomUUID(), nickname = "user", image = null),
        )
        // convertToResponse(post: TablePost, ...)는 커스텀 Kotlin 메서드라 non-null 파라미터에
        // any()의 실제 반환값(null)을 그대로 넘기면 NPE가 난다 - anyUuid()와 같은 이유의 우회.
        `when`(postResponseAssembler.convertToResponse(anyUuid(), anyUuid())).thenReturn(response)
        return response
    }

    @Test
    fun `getPostById returns post when owner views their own private post`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = privatePost(postId, ownerId)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        stubAssemblerResponse(postId)

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
        verifyNoInteractions(postResponseAssembler)
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
        verifyNoInteractions(postResponseAssembler)
    }

    @Test
    fun `getPostById 는 로그인 사용자가 조회하면 post_views 를 upsert 한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        stubAssemblerResponse(postId)

        postService.getPostById(postId, ownerId)

        verify(postViewRepository).upsertView(ownerId, postId)
    }

    @Test
    fun `getPostById 는 비로그인 사용자가 조회하면 post_views 를 건드리지 않는다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        stubAssemblerResponse(postId)

        postService.getPostById(postId, null)

        verifyNoInteractions(postViewRepository)
    }

    @Test
    fun `getPostById 는 조회한 post 와 currentUserId 를 그대로 assembler 에 위임한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        stubAssemblerResponse(postId)

        postService.getPostById(postId, ownerId)

        verify(postResponseAssembler).convertToResponse(post, ownerId)
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

    @Test
    fun `크롤링에 실패해도 fallbackContent가 있으면 PENDING으로 저장하고 AI 이벤트를 발행한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/fallback"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        // stubMetadataExtraction이 이미 pageContent = null(크롤링 실패 상황)을 반환한다 - 이 테스트의
        // 관심사가 바로 그 상황에서 fallbackContent가 대신 쓰이는지이므로 그대로 재사용한다.
        stubMetadataExtraction(url)
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.createPost(userId, PostCreateRequest(url = url), fallbackContent = "RSS 본문")

        assertEquals(AiStatus.PENDING, savedPostCaptor.value.aiStatus)
        val eventCaptor = ArgumentCaptor.forClass(PostCreatedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertEquals("RSS 본문", eventCaptor.value.content)
        assertEquals(postId, eventCaptor.value.postId)
    }

    @Test
    fun `fallbackContent 없이 등록하는 사람 경로는 크롤링 실패 시 NONE 그대로다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/human-crawl-fail"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        stubMetadataExtraction(url)
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.createPost(userId, PostCreateRequest(url = url))

        assertEquals(AiStatus.NONE, savedPostCaptor.value.aiStatus)
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `folderIds 를 지정하지 않고 등록하면 북마크를 생성하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/no-bookmark"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        stubMetadataExtraction(url)
        `when`(postRepository.save(any())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

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
        stubAssemblerResponse(postId, isBookmarked = true, bookmarkFolderIds = listOf(folderId1, folderId2))

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
        stubAssemblerResponse(postId, isBookmarked = true)

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
        stubAssemblerResponse(postId, isBookmarked = true, bookmarkFolderIds = listOf(folderId))

        postService.createPost(userId, PostCreateRequest(url = url, folderIds = listOf(folderId, folderId)))

        verify(bookmarkFolderRepository).findAllById(listOf(folderId))
        verify(bookmarkFolderItemRepository, times(1)).insertIgnoreConflict(userId, postId, folderId)
    }

    @Test
    fun `updatePost 는 제목을 비우면 URL이 그대로여도 재크롤링해 제목을 다시 채운다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://youtu.be/abc"
        val post = TablePost(id = postId, userId = userId, url = url, title = "- YouTube", isPrivate = false)
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "실제 영상 제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(urlMetadataExtractor.extract(url)).thenReturn(
            UrlMetadata(title = "실제 영상 제목", description = null, ogImage = null, tags = emptyList(), pageContent = null),
        )
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.updatePost(postId, userId, PostUpdateRequest(title = ""))

        assertEquals("실제 영상 제목", savedPostCaptor.value.title)
        verify(urlMetadataExtractor).extract(url)
        // URL은 그대로이므로 등록 시점에 이미 통과한 값을 다시 검증하지 않는다.
        verifyNoInteractions(safeUrlValidator)
    }

    @Test
    fun `updatePost 는 재수집 제목이 빈약하면 기존 제목을 그대로 둔다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://youtu.be/abc"
        val post = TablePost(id = postId, userId = userId, url = url, title = "기존 좋은 제목", isPrivate = false)
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "기존 좋은 제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(urlMetadataExtractor.extract(url)).thenReturn(
            UrlMetadata(title = "- YouTube", description = null, ogImage = null, tags = emptyList(), pageContent = null),
        )
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.updatePost(postId, userId, PostUpdateRequest(title = ""))

        assertEquals("기존 좋은 제목", savedPostCaptor.value.title)
    }

    @Test
    fun `updatePost 는 제목만 비운 재수집에서 기존 설명·태그·AI 요약을 덮지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/a"
        val post = TablePost(
            id = postId,
            userId = userId,
            url = url,
            title = "기존 제목",
            description = "기존 설명",
            tags = listOf("기존태그"),
            ogImage = "기존 이미지",
            aiSummary = "기존 요약",
            aiStatus = AiStatus.COMPLETED,
            isPrivate = false,
        )
        val savedPost = post

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(urlMetadataExtractor.extract(url)).thenReturn(
            UrlMetadata(
                title = "새 제목",
                description = "새 설명",
                ogImage = "새 이미지",
                tags = listOf("새태그"),
                pageContent = null,
            ),
        )
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.updatePost(postId, userId, PostUpdateRequest(title = ""))

        assertEquals("새 제목", savedPostCaptor.value.title)
        assertEquals("기존 설명", savedPostCaptor.value.description)
        assertEquals(listOf("기존태그"), savedPostCaptor.value.tags)
        assertEquals("기존 이미지", savedPostCaptor.value.ogImage)
        assertEquals("기존 요약", savedPostCaptor.value.aiSummary)
        assertEquals(AiStatus.COMPLETED, savedPostCaptor.value.aiStatus)
    }

    @Test
    fun `updatePost 는 제목만 비운 재수집의 AI 이벤트에 기존 태그를 그대로 넘긴다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/a"
        val post = TablePost(
            id = postId,
            userId = userId,
            url = url,
            title = "기존 제목",
            tags = listOf("기존태그1", "기존태그2"),
            isPrivate = false,
        )
        val savedPost = post

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(urlMetadataExtractor.extract(url)).thenReturn(
            UrlMetadata(
                title = "새 제목",
                description = null,
                ogImage = null,
                // 크롤링 태그는 호스트 하나뿐이다 - 그대로 넘기면 기존 AI 태그가 사라진다(회귀 방지).
                tags = listOf("example.com"),
                pageContent = "본문",
            ),
        )
        `when`(postRepository.save(any())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.updatePost(postId, userId, PostUpdateRequest(title = ""))

        val eventCaptor = ArgumentCaptor.forClass(PostCreatedEvent::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertEquals(listOf("기존태그1", "기존태그2"), eventCaptor.value.existingTags)
        assertEquals("새 제목", eventCaptor.value.title)
    }

    @Test
    fun `updatePost 는 URL을 바꾸면 메타데이터를 통째로 덮고 aiSummary 를 리셋한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val oldUrl = "https://old.example.com"
        val newUrl = "https://new.example.com"
        val post = TablePost(
            id = postId,
            userId = userId,
            url = oldUrl,
            title = "기존 제목",
            description = "기존 설명",
            tags = listOf("기존태그"),
            ogImage = "기존 이미지",
            aiSummary = "기존 요약",
            aiStatus = AiStatus.COMPLETED,
            isPrivate = false,
        )
        val savedPost = post

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        `when`(urlMetadataExtractor.extract(newUrl)).thenReturn(
            UrlMetadata(
                title = "새 링크 제목",
                description = "새 링크 설명",
                ogImage = "새 링크 이미지",
                tags = listOf("new.example.com"),
                pageContent = null,
            ),
        )
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.updatePost(postId, userId, PostUpdateRequest(url = newUrl))

        verify(safeUrlValidator).validate(newUrl)
        assertEquals(newUrl, savedPostCaptor.value.url)
        assertEquals("새 링크 제목", savedPostCaptor.value.title)
        assertEquals("새 링크 설명", savedPostCaptor.value.description)
        assertEquals(listOf("new.example.com"), savedPostCaptor.value.tags)
        assertEquals("새 링크 이미지", savedPostCaptor.value.ogImage)
        assertEquals(null, savedPostCaptor.value.aiSummary)
        assertEquals(AiStatus.NONE, savedPostCaptor.value.aiStatus)
    }

    @Test
    fun `updatePost 는 제목을 직접 입력하면 재크롤링하지 않는다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/a"
        val post = TablePost(id = postId, userId = userId, url = url, title = "기존 제목", isPrivate = false)
        val savedPost = post

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.updatePost(postId, userId, PostUpdateRequest(title = "사용자가 직접 쓴 제목"))

        assertEquals("사용자가 직접 쓴 제목", savedPostCaptor.value.title)
        verify(urlMetadataExtractor, never()).extract(ArgumentMatchers.anyString())
    }
}
