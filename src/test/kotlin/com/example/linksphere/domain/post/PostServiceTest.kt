package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.TableBookmarkFolder
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.CursorCodec
import com.example.linksphere.global.common.KeysetSlice
import com.example.linksphere.global.common.Paging
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.DuplicatePostException
import com.example.linksphere.global.exception.EmailNotVerifiedException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import com.example.linksphere.infra.ai.GeminiService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
import org.springframework.data.domain.PageImpl
import java.time.LocalDateTime
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

    @Mock private lateinit var geminiService: GeminiService

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var linkPreviewService: LinkPreviewService

    @Mock private lateinit var advisoryLock: AdvisoryLock

    @InjectMocks private lateinit var postService: PostService

    private fun privatePost(postId: UUID, ownerId: UUID) = TablePost(
        id = postId,
        userId = ownerId,
        url = "https://example.com",
        title = "제목",
        isPrivate = true,
    )

    // createPost 최상단의 이메일 인증 게이트가 조회하는 회원 - 인증된 상태로 기본값을 준다.
    private fun stubVerifiedMember(userId: UUID) {
        `when`(memberRepository.findById(userId)).thenReturn(
            Optional.of(TableMember(id = userId, email = "$userId@example.com", password = "pw", nickname = "tester", emailVerified = true)),
        )
    }

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
    fun `getPostById 는 조회수와 post_views 를 바꾸지 않는다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))
        stubAssemblerResponse(postId)

        postService.getPostById(postId, ownerId)

        verify(postRepository, never()).incrementViewCount(anyUuid())
        verifyNoInteractions(postViewRepository)
    }

    @Test
    fun `recordView 는 로그인 사용자면 조회수를 올리고 post_views 를 upsert 한다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        postService.recordView(postId, ownerId)

        verify(postRepository).incrementViewCount(postId)
        verify(postViewRepository).upsertView(ownerId, postId)
    }

    @Test
    fun `recordView 는 비로그인이면 조회수만 올리고 post_views 를 건드리지 않는다`() {
        val ownerId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val post = TablePost(id = postId, userId = ownerId, url = "https://example.com", title = "제목", isPrivate = false)

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(post))

        postService.recordView(postId, null)

        verify(postRepository).incrementViewCount(postId)
        verifyNoInteractions(postViewRepository)
    }

    @Test
    fun `recordView 는 남의 비공개 글이면 PostNotFoundException 이고 기록하지 않는다`() {
        val ownerId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val postId = UUID.randomUUID()

        `when`(postRepository.findById(postId)).thenReturn(Optional.of(privatePost(postId, ownerId)))

        assertThrows(PostNotFoundException::class.java) {
            postService.recordView(postId, otherUserId)
        }
        verify(postRepository, never()).incrementViewCount(anyUuid())
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

    // 잠금·시간 창이 실제로 겹친 요청을 막는지는 PostIdempotencyIntegrationTest가 Postgres로 본다
    @Test
    fun `키 없는 봇 경로는 잠금과 시간 창을 건너뛴다`() {
        val userId = UUID.randomUUID()
        val url = "https://example.com/post"
        stubVerifiedMember(userId)
        stubMetadataExtraction(url)
        stubAssemblerResponse(UUID.randomUUID())
        `when`(postRepository.save(anyUuid<TablePost>())).thenAnswer { it.arguments[0] }

        postService.createPost(userId, PostCreateRequest(url = url))

        verifyNoInteractions(advisoryLock)
        verify(postRepository, never()).existsByUserIdAndUrlAndCreatedAtAfter(anyUuid(), anyUuid(), anyUuid())
    }

    @Test
    fun `createPost는 다른 키로 1시간 안에 같은 URL을 등록하면 크롤링 전에 DuplicatePostException을 던진다`() {
        val userId = UUID.randomUUID()
        val url = "https://example.com/post"
        stubVerifiedMember(userId)
        `when`(postRepository.existsByUserIdAndUrlAndCreatedAtAfter(anyUuid(), anyUuid(), anyUuid())).thenReturn(true)

        assertThrows(DuplicatePostException::class.java) {
            postService.createPost(userId, PostCreateRequest(url = url), idempotencyKey = UUID.randomUUID())
        }

        verify(advisoryLock).lockForTransaction("post-create:$userId:$url")
        verifyNoInteractions(urlMetadataExtractor)
    }

    @Test
    fun `createPost는 잠금을 기다리는 동안 같은 키의 글이 생겼으면 그 글을 돌려주고 시간 창을 보지 않는다`() {
        val userId = UUID.randomUUID()
        val key = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/post"
        stubVerifiedMember(userId)
        val response = stubAssemblerResponse(postId)
        `when`(postRepository.findByUserIdAndIdempotencyKey(userId, key))
            .thenReturn(TablePost(id = postId, userId = userId, url = url, title = "제목", idempotencyKey = key))

        val result = postService.createPost(userId, PostCreateRequest(url = url), idempotencyKey = key)

        assertEquals(response, result)
        verify(postRepository, never()).existsByUserIdAndUrlAndCreatedAtAfter(anyUuid(), anyUuid(), anyUuid())
        verify(postRepository, never()).save(anyUuid<TablePost>())
        verifyNoInteractions(urlMetadataExtractor)
    }

    @Test
    fun `createPost는 이메일 미인증 회원이면 크롤링 전에 EmailNotVerifiedException을 던진다`() {
        val userId = UUID.randomUUID()
        val url = "https://example.com/unverified"
        `when`(memberRepository.findById(userId)).thenReturn(
            Optional.of(TableMember(id = userId, email = "a@a.com", password = "pw", nickname = "tester", emailVerified = false)),
        )

        assertThrows(EmailNotVerifiedException::class.java) {
            postService.createPost(userId, PostCreateRequest(url = url))
        }

        verifyNoInteractions(urlMetadataExtractor)
    }

    @Test
    fun `크롤링에 실패해도 fallbackContent가 있으면 PENDING으로 저장하고 AI 이벤트를 발행한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/fallback"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "제목", isPrivate = false)

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

        stubVerifiedMember(userId)
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

    @Test
    fun `getAllPosts는 allowSemanticSearch=false면 검색어가 있어도 임베딩을 부르지 않고 키워드로만 조회한다`() {
        val pageable = Paging.pageRequest(0, 10)
        `when`(postRepository.findPosts(null, "spring boot", null, null, null, pageable, null))
            .thenReturn(PageImpl(listOf(privatePost(UUID.randomUUID(), UUID.randomUUID())), pageable, 1))

        postService.getAllPosts(null, "spring boot", null, null, 0, 10, null, allowSemanticSearch = false)

        verifyNoInteractions(geminiService)
    }

    @Test
    fun `getAllPosts는 size를 최대 페이지 크기로 잘라 조회한다`() {
        val capped = Paging.pageRequest(0, Paging.MAX_PAGE_SIZE)
        `when`(postRepository.findPosts(null, null, null, null, null, capped, null))
            .thenReturn(PageImpl(emptyList(), capped, 0))

        postService.getAllPosts(null, null, null, null, 0, 100_000, null)

        verify(postRepository).findPosts(null, null, null, null, null, capped, null)
    }

    // ── cursor 목록 ──────────────────────────────────────

    private val relevanceKeys = listOf<Comparable<*>>(80000L, LocalDateTime.of(2026, 11, 5, 12, 0, 0, 123_456_000), UUID.randomUUID())

    private fun relevanceCursor(semantic: Boolean, corrected: Boolean? = null) = CursorCodec.encode(PostListSort.RELEVANCE.name, relevanceKeys, semantic, corrected)

    private fun relevanceState(cursor: String?) = CursorCodec.decode(cursor!!, PostListSort.RELEVANCE.name, PostListSort.RELEVANCE.keyTypes)

    private fun searchPost() = TablePost(id = UUID.randomUUID(), userId = UUID.randomUUID(), url = "https://example.com", title = "제목")

    @Test
    fun `getPostsByCursor는 검색 첫 쪽에서 의미 검색 여부를 cursor에 담고 건수를 센다`() {
        val embedding = floatArrayOf(1f)
        `when`(geminiService.embedQuery(PostEmbeddingText.query("spring"))).thenReturn(embedding)
        `when`(postRepository.findPostsAfter(null, "spring", null, null, null, PostListSort.RELEVANCE, null, 10, embedding, true))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys, 5L))

        val result = postService.getPostsByCursor(null, "spring", null, null, null, 10, null)

        assertEquals(relevanceKeys, relevanceState(result.nextCursor).keys)
        assertEquals(true, relevanceState(result.nextCursor).semantic)
        assertNull(result.page)
        assertNull(result.totalElements)
    }

    @Test
    fun `getPostsByCursor는 의미 검색으로 시작한 다음 쪽에서 임베딩을 다시 받고 건수는 세지 않는다`() {
        val embedding = floatArrayOf(1f)
        var consumed = 0
        `when`(geminiService.embedQuery(PostEmbeddingText.query("spring"))).thenReturn(embedding)
        `when`(postRepository.findPostsAfter(null, "spring", null, null, null, PostListSort.RELEVANCE, relevanceKeys, 10, embedding, false))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys))

        val result =
            postService.getPostsByCursor(null, "spring", null, null, relevanceCursor(semantic = true), 10, null) {
                consumed++
                true
            }

        assertEquals(1, consumed)
        assertEquals(true, relevanceState(result.nextCursor).semantic)
    }

    // 키워드로 시작한 뒤 의미 점수가 켜지면 키워드 점수 기준의 cursor와 맞지 않아 글을 놓친다(docs/PAGINATION.md)
    @Test
    fun `getPostsByCursor는 키워드로 시작한 뒤쪽에서 임베딩과 호출 한도를 쓰지 않는다`() {
        `when`(postRepository.findPostsAfter(null, "spring", null, null, null, PostListSort.RELEVANCE, relevanceKeys, 10, null, false))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys))

        val result =
            postService.getPostsByCursor(null, "spring", null, null, relevanceCursor(semantic = false), 10, null) {
                throw AssertionError("호출 한도를 쓰면 안 된다")
            }

        verifyNoInteractions(geminiService)
        assertEquals(false, relevanceState(result.nextCursor).semantic)
    }

    @Test
    fun `getPostsByCursor는 호출 한도를 넘으면 키워드로 내려가 뒤쪽도 그대로 유지한다`() {
        `when`(postRepository.findPostsAfter(null, "spring", null, null, null, PostListSort.RELEVANCE, relevanceKeys, 10, null, false))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys))

        val result = postService.getPostsByCursor(null, "spring", null, null, relevanceCursor(semantic = true), 10, null) { false }

        verifyNoInteractions(geminiService)
        assertEquals(false, relevanceState(result.nextCursor).semantic)
    }

    @Test
    fun `getPostsByCursor는 뒤쪽에서 임베딩이 실패하면 키워드로 내려가 뒤쪽도 그대로 유지한다`() {
        `when`(geminiService.embedQuery(PostEmbeddingText.query("spring"))).thenReturn(null)
        `when`(postRepository.findPostsAfter(null, "spring", null, null, null, PostListSort.RELEVANCE, relevanceKeys, 10, null, false))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys))

        val result = postService.getPostsByCursor(null, "spring", null, null, relevanceCursor(semantic = true), 10, null)

        assertEquals(false, relevanceState(result.nextCursor).semantic)
    }

    @Test
    fun `getPostsByCursor는 첫 쪽이 비면 자판 보정으로 재검색하고 cursor에 표시한다`() {
        `when`(geminiService.embedQuery(PostEmbeddingText.query("spdlqj"))).thenReturn(null)
        `when`(postRepository.findPostsAfter(null, "spdlqj", null, null, null, PostListSort.RELEVANCE, null, 10, null, true))
            .thenReturn(KeysetSlice(emptyList(), null, 0L))
        `when`(postRepository.findPostsAfter(null, "네이버", null, null, null, PostListSort.RELEVANCE, null, 10, null, true))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys, 1L))

        val result = postService.getPostsByCursor(null, "spdlqj", null, null, null, 10, null)

        assertEquals("네이버", result.correctedSearch)
        assertEquals(true, relevanceState(result.nextCursor).corrected)
        assertEquals(false, relevanceState(result.nextCursor).semantic)
    }

    @Test
    fun `getPostsByCursor는 보정으로 시작한 뒤쪽을 보정어로 읽고 응답에 보정어를 유지한다`() {
        `when`(postRepository.findPostsAfter(null, "네이버", null, null, null, PostListSort.RELEVANCE, relevanceKeys, 10, null, false))
            .thenReturn(KeysetSlice(listOf(searchPost()), relevanceKeys))

        val result = postService.getPostsByCursor(null, "spdlqj", null, null, relevanceCursor(semantic = false, corrected = true), 10, null)

        verifyNoInteractions(geminiService)
        assertEquals("네이버", result.correctedSearch)
        assertEquals(true, relevanceState(result.nextCursor).corrected)
    }

    // 예전에는 쪽마다 totalElements == 0으로 판정했다. 건수를 세지 않으니 빈 마지막 쪽을 0건 검색으로 오인하면 안 된다.
    @Test
    fun `getPostsByCursor는 뒤쪽이 비어도 자판 보정을 시도하지 않는다`() {
        `when`(postRepository.findPostsAfter(null, "spdlqj", null, null, null, PostListSort.RELEVANCE, relevanceKeys, 10, null, false))
            .thenReturn(KeysetSlice(emptyList()))

        val result = postService.getPostsByCursor(null, "spdlqj", null, null, relevanceCursor(semantic = false), 10, null)

        assertNull(result.correctedSearch)
        assertNull(result.nextCursor)
    }

    @Test
    fun `getPostsByCursor는 검색이 아니면 임베딩과 호출 한도를 쓰지 않고 마지막 쪽이면 cursor가 없다`() {
        `when`(postRepository.findPostsAfter(null, null, null, null, null, PostListSort.LATEST, null, 10, null, false))
            .thenReturn(KeysetSlice(listOf(searchPost())))

        val result =
            postService.getPostsByCursor(null, null, null, null, null, 10, null) {
                throw AssertionError("호출 한도를 쓰면 안 된다")
            }

        verifyNoInteractions(geminiService)
        assertNull(result.nextCursor)
    }

    @Test
    fun `getPostsByCursor는 잘못된 cursor를 호출 한도를 쓰기 전에 거절한다`() {
        val latestCursor = CursorCodec.encode(PostListSort.LATEST.name, listOf(LocalDateTime.of(2026, 11, 5, 12, 0), UUID.randomUUID()))

        listOf("not-a-cursor", latestCursor).forEach { cursor ->
            assertThrows(InvalidInputException::class.java) {
                postService.getPostsByCursor(null, "spring", null, null, cursor, 10, null) {
                    throw AssertionError("호출 한도를 쓰면 안 된다")
                }
            }
        }
    }

    @Test
    fun `getPostsByCursor는 size를 최대 페이지 크기로 자른다`() {
        `when`(postRepository.findPostsAfter(null, null, null, null, null, PostListSort.LATEST, null, Paging.MAX_PAGE_SIZE, null, false))
            .thenReturn(KeysetSlice(emptyList()))

        postService.getPostsByCursor(null, null, null, null, null, 100_000, null)

        verify(postRepository).findPostsAfter(null, null, null, null, null, PostListSort.LATEST, null, Paging.MAX_PAGE_SIZE, null, false)
    }

    @Test
    fun `createPost는 10분 안에 미리 본 링크면 크롤링하지 않고 미리보기 결과로 저장한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val url = "https://example.com/previewed"
        val savedPost = TablePost(id = postId, userId = userId, url = url, title = "미리 본 제목", isPrivate = false)

        stubVerifiedMember(userId)
        `when`(linkPreviewService.findFresh(url)).thenReturn(
            UrlMetadata(title = "미리 본 제목", description = "설명", ogImage = "https://example.com/og.png", tags = listOf("example.com"), pageContent = null),
        )
        val savedPostCaptor = ArgumentCaptor.forClass(TablePost::class.java)
        `when`(postRepository.save(savedPostCaptor.capture())).thenReturn(savedPost)
        stubAssemblerResponse(postId)

        postService.createPost(userId, PostCreateRequest(url = url))

        assertEquals("미리 본 제목", savedPostCaptor.value.title)
        assertEquals("https://example.com/og.png", savedPostCaptor.value.ogImage)
        verifyNoInteractions(urlMetadataExtractor)
    }
}
