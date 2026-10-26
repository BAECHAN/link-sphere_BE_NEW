package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.PostResponseAssembler
import com.example.linksphere.domain.post.TablePost
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.DuplicateFolderNameException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.exception.InvalidInputException
import com.example.linksphere.global.exception.PostNotFoundException
import com.example.linksphere.infra.ai.GeminiService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID

private data class TestFolderBookmarkCount(override val folderId: UUID, override val count: Long) : FolderBookmarkCount

private data class TestFolderLastUsed(override val folderId: UUID, override val lastUsedAt: LocalDateTime) : FolderLastUsed

@ExtendWith(MockitoExtension::class)
class BookmarkFolderServiceTest {

    @Mock private lateinit var bookmarkFolderRepository: BookmarkFolderRepository

    @Mock private lateinit var bookmarkRepository: BookmarkRepository

    @Mock private lateinit var bookmarkFolderItemRepository: BookmarkFolderItemRepository

    @Mock private lateinit var postResponseAssembler: PostResponseAssembler

    @Mock private lateinit var geminiService: GeminiService

    @Mock private lateinit var postRepository: PostRepository

    @InjectMocks private lateinit var bookmarkFolderService: BookmarkFolderService

    // ── deleteFolder ─────────────────────────────────────────────

    @Test
    fun `deleteFolder 는 해당 폴더 소속만 삭제하고 다른 폴더의 북마크는 건드리지 않는다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))

        bookmarkFolderService.deleteFolder(userId, folderId)

        verify(bookmarkFolderItemRepository).deleteByFolderId(folderId)
        verify(bookmarkFolderRepository).delete(folder)
        // 그 외엔 아무것도 건드리지 않는다 — 다른 폴더 소속도, 북마크 자체(bookmarks row)도.
        verifyNoMoreInteractions(bookmarkFolderItemRepository)
        verifyNoInteractions(bookmarkRepository)
    }

    @Test
    fun `deleteFolder 는 타인 폴더면 ForbiddenException`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = otherUserId, name = "개발")

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))

        assertThrows(ForbiddenException::class.java) {
            bookmarkFolderService.deleteFolder(userId, folderId)
        }
        verifyNoInteractions(bookmarkFolderItemRepository)
    }

    // ── getFolders ───────────────────────────────────────────────

    @Test
    fun `getFolders 는 폴더 수와 무관하게 카운트 쿼리를 1회만 호출한다`() {
        val userId = UUID.randomUUID()
        val folders = listOf(
            TableBookmarkFolder(userId = userId, name = "개발", sortOrder = 0),
            TableBookmarkFolder(userId = userId, name = "디자인", sortOrder = 1),
            TableBookmarkFolder(userId = userId, name = "나중에 읽기", sortOrder = 2),
        )

        `when`(bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)).thenReturn(folders)
        `when`(bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkRepository.countUncategorizedByUserId(userId)).thenReturn(0L)

        bookmarkFolderService.getFolders(userId)

        verify(bookmarkFolderItemRepository, times(1)).countByUserIdGroupByFolderId(userId)
    }

    @Test
    fun `getFolders 는 그룹 카운트 결과를 폴더별 bookmarkCount 로 매핑한다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")

        `when`(bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)).thenReturn(listOf(folder))
        `when`(bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId))
            .thenReturn(listOf(TestFolderBookmarkCount(folderId, 7L)))
        `when`(bookmarkRepository.countUncategorizedByUserId(userId)).thenReturn(0L)

        val result = bookmarkFolderService.getFolders(userId)

        assertEquals(7, result.folders.single().bookmarkCount)
    }

    @Test
    fun `getFolders 의 uncategorizedCount 는 소속 0개 북마크 수다`() {
        val userId = UUID.randomUUID()

        `when`(bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)).thenReturn(emptyList())
        `when`(bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkRepository.countUncategorizedByUserId(userId)).thenReturn(5L)

        val result = bookmarkFolderService.getFolders(userId)

        assertEquals(5, result.uncategorizedCount)
    }

    @Test
    fun `getFolders 는 폴더 수와 무관하게 lastUsedAt 조회 쿼리를 1회만 호출한다`() {
        val userId = UUID.randomUUID()
        val folders = listOf(
            TableBookmarkFolder(userId = userId, name = "개발", sortOrder = 0),
            TableBookmarkFolder(userId = userId, name = "디자인", sortOrder = 1),
        )

        `when`(bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)).thenReturn(folders)
        `when`(bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkFolderItemRepository.findLastUsedByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkRepository.countUncategorizedByUserId(userId)).thenReturn(0L)

        bookmarkFolderService.getFolders(userId)

        verify(bookmarkFolderItemRepository, times(1)).findLastUsedByUserIdGroupByFolderId(userId)
    }

    @Test
    fun `getFolders 는 lastUsedAt 조회 결과를 폴더별로 매핑한다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")
        val lastUsedAt = LocalDateTime.of(2026, 8, 1, 12, 0)

        `when`(bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)).thenReturn(listOf(folder))
        `when`(bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkFolderItemRepository.findLastUsedByUserIdGroupByFolderId(userId))
            .thenReturn(listOf(TestFolderLastUsed(folderId, lastUsedAt)))
        `when`(bookmarkRepository.countUncategorizedByUserId(userId)).thenReturn(0L)

        val result = bookmarkFolderService.getFolders(userId)

        assertEquals(lastUsedAt, result.folders.single().lastUsedAt)
    }

    @Test
    fun `getFolders 는 한 번도 저장 안 된 폴더면 lastUsedAt 이 null 이다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")

        `when`(bookmarkFolderRepository.findByUserIdOrderBySortOrderAsc(userId)).thenReturn(listOf(folder))
        `when`(bookmarkFolderItemRepository.countByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkFolderItemRepository.findLastUsedByUserIdGroupByFolderId(userId)).thenReturn(emptyList())
        `when`(bookmarkRepository.countUncategorizedByUserId(userId)).thenReturn(0L)

        val result = bookmarkFolderService.getFolders(userId)

        assertEquals(null, result.folders.single().lastUsedAt)
    }

    // ── getBookmarkedPosts: folderKey 3-way 매핑 ────────────────────

    @Test
    fun `getBookmarkedPosts 는 folderKey all 이면 폴더 필터 없이 조회한다`() {
        val userId = UUID.randomUUID()
        val pageable = PageRequest.of(0, 10)
        val page = PageImpl<TablePost>(emptyList(), pageable, 0)

        `when`(bookmarkRepository.findBookmarkedPosts(userId, null, false, "latest", null, pageable))
            .thenReturn(page)
        `when`(postResponseAssembler.buildResponsesFromPosts(emptyList(), userId)).thenReturn(emptyList())

        bookmarkFolderService.getBookmarkedPosts(userId, "all", null, null, 0, 10)

        verify(bookmarkRepository).findBookmarkedPosts(userId, null, false, "latest", null, pageable)
    }

    @Test
    fun `getBookmarkedPosts 는 sort viewed 를 그대로 전달한다`() {
        val userId = UUID.randomUUID()
        val pageable = PageRequest.of(0, 10)
        val page = PageImpl<TablePost>(emptyList(), pageable, 0)

        `when`(bookmarkRepository.findBookmarkedPosts(userId, null, false, "viewed", null, pageable))
            .thenReturn(page)
        `when`(postResponseAssembler.buildResponsesFromPosts(emptyList(), userId)).thenReturn(emptyList())

        bookmarkFolderService.getBookmarkedPosts(userId, "all", "viewed", null, 0, 10)

        verify(bookmarkRepository).findBookmarkedPosts(userId, null, false, "viewed", null, pageable)
    }

    @Test
    fun `getBookmarkedPosts 는 folderKey uncategorized 면 미분류만 조회한다`() {
        val userId = UUID.randomUUID()
        val pageable = PageRequest.of(0, 10)
        val page = PageImpl<TablePost>(emptyList(), pageable, 0)

        `when`(bookmarkRepository.findBookmarkedPosts(userId, null, true, "latest", null, pageable))
            .thenReturn(page)
        `when`(postResponseAssembler.buildResponsesFromPosts(emptyList(), userId)).thenReturn(emptyList())

        bookmarkFolderService.getBookmarkedPosts(userId, "uncategorized", null, null, 0, 10)

        verify(bookmarkRepository).findBookmarkedPosts(userId, null, true, "latest", null, pageable)
    }

    @Test
    fun `getBookmarkedPosts 는 folderKey 가 UUID 면 해당 폴더로 조회한다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")
        val pageable = PageRequest.of(0, 10)
        val page = PageImpl<TablePost>(emptyList(), pageable, 0)

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))
        `when`(bookmarkRepository.findBookmarkedPosts(userId, folderId, false, "latest", null, pageable))
            .thenReturn(page)
        `when`(postResponseAssembler.buildResponsesFromPosts(emptyList(), userId)).thenReturn(emptyList())

        bookmarkFolderService.getBookmarkedPosts(userId, folderId.toString(), null, null, 0, 10)

        verify(bookmarkRepository).findBookmarkedPosts(userId, folderId, false, "latest", null, pageable)
    }

    @Test
    fun `getBookmarkedPosts 는 잘못된 folderKey 면 InvalidInputException`() {
        val userId = UUID.randomUUID()

        assertThrows(InvalidInputException::class.java) {
            bookmarkFolderService.getBookmarkedPosts(userId, "not-a-uuid", null, null, 0, 10)
        }
    }

    @Test
    fun `getBookmarkedPosts 는 없는 폴더면 BookmarkFolderNotFoundException`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.empty())

        assertThrows(BookmarkFolderNotFoundException::class.java) {
            bookmarkFolderService.getBookmarkedPosts(userId, folderId.toString(), null, null, 0, 10)
        }
    }

    @Test
    fun `getBookmarkedPosts 는 타인 폴더면 ForbiddenException`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = otherUserId, name = "개발")

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))

        assertThrows(ForbiddenException::class.java) {
            bookmarkFolderService.getBookmarkedPosts(userId, folderId.toString(), null, null, 0, 10)
        }
    }

    @Test
    fun `getBookmarkedPosts 는 검색 결과 0건이면 한영 자판 보정으로 한 번 더 검색한다`() {
        val userId = UUID.randomUUID()
        val postId = UUID.randomUUID()
        val pageable = PageRequest.of(0, 10)
        val emptyPage = PageImpl<TablePost>(emptyList(), pageable, 0)
        val post = TablePost(id = postId, userId = userId, url = "https://naver.com", title = "네이버", isPrivate = false)
        val correctedPage = PageImpl(listOf(post), pageable, 1)

        // "spdlqj" 를 2벌식 자판으로 그대로 치면 "네이버" — HangulKeyboardConverter 보정 대상
        `when`(bookmarkRepository.findBookmarkedPosts(userId, null, false, "latest", "spdlqj", pageable))
            .thenReturn(emptyPage)
        `when`(bookmarkRepository.findBookmarkedPosts(userId, null, false, "latest", "네이버", pageable))
            .thenReturn(correctedPage)
        `when`(postResponseAssembler.buildResponsesFromPosts(listOf(post), userId, listOf("네이버"))).thenReturn(emptyList())

        val result = bookmarkFolderService.getBookmarkedPosts(userId, "all", null, "spdlqj", 0, 10)

        assertEquals("네이버", result.correctedSearch)
    }

    // ── batch add / remove ──────────────────────────────────────

    @Test
    fun `batchAddBookmarksToFolder 는 타인 폴더면 ForbiddenException`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = otherUserId, name = "개발")

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))

        assertThrows(ForbiddenException::class.java) {
            bookmarkFolderService.batchAddBookmarksToFolder(userId, folderId, listOf(UUID.randomUUID()))
        }
    }

    @Test
    fun `batchAddBookmarksToFolder 는 처리한 postId 수를 반환한다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")
        val postIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val posts = postIds.map {
            TablePost(id = it, userId = userId, url = "https://a.com", title = "글", isPrivate = false)
        }

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))
        `when`(postRepository.findAllById(postIds)).thenReturn(posts)

        val result = bookmarkFolderService.batchAddBookmarksToFolder(userId, folderId, postIds)

        assertEquals(2, result)
        postIds.forEach { postId ->
            verify(bookmarkRepository).insertIgnoreConflict(userId, postId)
            verify(bookmarkFolderItemRepository).insertIgnoreConflict(userId, postId, folderId)
        }
    }

    @Test
    fun `batchAddBookmarksToFolder 는 존재하지 않는 postId 가 섞여있으면 PostNotFoundException`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")
        val existingId = UUID.randomUUID()
        val missingId = UUID.randomUUID()
        val existingPost = TablePost(id = existingId, userId = userId, url = "https://a.com", title = "글", isPrivate = false)

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))
        `when`(postRepository.findAllById(listOf(existingId, missingId))).thenReturn(listOf(existingPost))

        assertThrows(PostNotFoundException::class.java) {
            bookmarkFolderService.batchAddBookmarksToFolder(userId, folderId, listOf(existingId, missingId))
        }
        verifyNoInteractions(bookmarkRepository)
    }

    @Test
    fun `batchAddBookmarksToFolder 는 타인의 비공개 글이 섞여있으면 PostNotFoundException`() {
        val userId = UUID.randomUUID()
        val otherUserId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val folder = TableBookmarkFolder(id = folderId, userId = userId, name = "개발")
        val postId = UUID.randomUUID()
        val privatePost = TablePost(id = postId, userId = otherUserId, url = "https://a.com", title = "글", isPrivate = true)

        `when`(bookmarkFolderRepository.findById(folderId)).thenReturn(Optional.of(folder))
        `when`(postRepository.findAllById(listOf(postId))).thenReturn(listOf(privatePost))

        assertThrows(PostNotFoundException::class.java) {
            bookmarkFolderService.batchAddBookmarksToFolder(userId, folderId, listOf(postId))
        }
        verifyNoInteractions(bookmarkRepository)
    }

    @Test
    fun `batchRemoveBookmarksFromFolder 는 실제로 삭제된 소속 수를 반환한다`() {
        val userId = UUID.randomUUID()
        val folderId = UUID.randomUUID()
        val postId1 = UUID.randomUUID()
        val postId2 = UUID.randomUUID()

        `when`(bookmarkFolderItemRepository.deleteByUserIdAndPostIdAndFolderId(userId, postId1, folderId))
            .thenReturn(1)
        `when`(bookmarkFolderItemRepository.deleteByUserIdAndPostIdAndFolderId(userId, postId2, folderId))
            .thenReturn(0)

        val result = bookmarkFolderService.batchRemoveBookmarksFromFolder(userId, folderId, listOf(postId1, postId2))

        assertEquals(1, result)
    }

    // ── batchDeleteBookmarks ─────────────────────────────────────

    @Test
    fun `batchDeleteBookmarks 는 소속도 함께 일괄 삭제하고 실제로 지운 개수를 돌려준다`() {
        val userId = UUID.randomUUID()
        val postId1 = UUID.randomUUID()
        val postId2 = UUID.randomUUID()
        val postIds = listOf(postId1, postId2)

        // 그 사이 다른 요청(단건 해제 등)이 하나를 먼저 지웠다면 일괄 DELETE는 1을 센다 — 예외 없이 그 수를 돌려준다
        `when`(bookmarkRepository.deleteByUserIdAndPostIdIn(userId, postIds)).thenReturn(1)

        val result = bookmarkFolderService.batchDeleteBookmarks(userId, postIds)

        assertEquals(1, result)
        verify(bookmarkFolderItemRepository).deleteByUserIdAndPostIdIn(userId, postIds)
        verify(bookmarkRepository).deleteByUserIdAndPostIdIn(userId, postIds)
    }

    // 사전 exists 확인은 통과했지만(동시 요청 레이스) 저장 시점에 DB 유니크 제약이 막은 경우 - 사전 확인 경로와 같은
    // DUPLICATE_FOLDER_NAME이 나가야 한다. 위반이 실제로 flush에서 난다는 건 BookmarkFolderRepositoryIntegrationTest가 보인다.
    @Test
    fun `createFolder 는 저장 시점의 이름 유니크 제약 위반을 DuplicateFolderNameException 으로 바꾼다`() {
        val userId = UUID.randomUUID()
        val captor = ArgumentCaptor.forClass(TableBookmarkFolder::class.java)
        `when`(bookmarkFolderRepository.saveAndFlush(captor.capture()))
            .thenThrow(duplicateKey("uk_bookmark_folders_user_name"))

        assertThrows(DuplicateFolderNameException::class.java) {
            bookmarkFolderService.createFolder(userId, CreateFolderRequest(name = "읽을거리"))
        }
    }

    @Test
    fun `createFolder 는 다른 제약 위반은 그대로 다시 던진다`() {
        val captor = ArgumentCaptor.forClass(TableBookmarkFolder::class.java)
        `when`(bookmarkFolderRepository.saveAndFlush(captor.capture()))
            .thenThrow(duplicateKey("bookmark_folders_pkey"))

        assertThrows(DataIntegrityViolationException::class.java) {
            bookmarkFolderService.createFolder(UUID.randomUUID(), CreateFolderRequest(name = "읽을거리"))
        }
    }

    @Test
    fun `updateFolder 는 flush 시점의 이름 유니크 제약 위반을 DuplicateFolderNameException 으로 바꾼다`() {
        val userId = UUID.randomUUID()
        val folder = TableBookmarkFolder(userId = userId, name = "나중에")
        `when`(bookmarkFolderRepository.findById(folder.id)).thenReturn(Optional.of(folder))
        doThrow(duplicateKey("uk_bookmark_folders_user_name")).`when`(bookmarkFolderRepository).flush()

        assertThrows(DuplicateFolderNameException::class.java) {
            bookmarkFolderService.updateFolder(userId, folder.id, UpdateFolderRequest(name = "읽을거리"))
        }
    }

    // DB 컬럼 varchar(100)보다 긴 이름은 저장 전에 400으로 막는다 - 예전에는 무결성 위반이 409 DUPLICATE_RESOURCE로 나갔다
    @Test
    fun `createFolder 는 100자를 넘는 이름을 InvalidInputException 으로 막고 저장하지 않는다`() {
        assertThrows(InvalidInputException::class.java) {
            bookmarkFolderService.createFolder(UUID.randomUUID(), CreateFolderRequest(name = "가".repeat(101)))
        }

        verifyNoInteractions(bookmarkFolderRepository)
    }

    @Test
    fun `updateFolder 는 100자를 넘는 이름을 InvalidInputException 으로 막는다`() {
        val userId = UUID.randomUUID()
        val folder = TableBookmarkFolder(userId = userId, name = "나중에")
        `when`(bookmarkFolderRepository.findById(folder.id)).thenReturn(Optional.of(folder))

        assertThrows(InvalidInputException::class.java) {
            bookmarkFolderService.updateFolder(userId, folder.id, UpdateFolderRequest(name = "가".repeat(101)))
        }
    }

    // 길이는 Postgres varchar처럼 문자(코드포인트) 수로 센다 - 이모지 100개는 UTF-16으로 200이지만 통과해야 한다
    @Test
    fun `createFolder 는 길이를 코드포인트로 세 이모지 100개 이름을 받는다`() {
        val captor = ArgumentCaptor.forClass(TableBookmarkFolder::class.java)
        `when`(bookmarkFolderRepository.saveAndFlush(captor.capture())).thenAnswer { it.arguments[0] }

        bookmarkFolderService.createFolder(UUID.randomUUID(), CreateFolderRequest(name = "😀".repeat(100)))

        assertEquals(100, captor.value.name.codePointCount(0, captor.value.name.length))
    }

    private fun duplicateKey(constraint: String) = DataIntegrityViolationException("duplicate key value violates unique constraint \"$constraint\"")
}
