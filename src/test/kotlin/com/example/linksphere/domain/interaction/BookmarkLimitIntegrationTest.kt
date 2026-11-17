package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentCountAdapter
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.post.PostCandidateService
import com.example.linksphere.domain.post.PostLookupService
import com.example.linksphere.domain.post.PostResponseAssembler
import com.example.linksphere.domain.post.PostSearchFlow
import com.example.linksphere.global.exception.BookmarkLimitExceededException
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

// 북마크 저장 한도(BookmarkLimits.MAX_BOOKMARKS_PER_MEMBER)를 실제 Postgres에서 본다(docs/plans/2026-11-17-bookmark-limit-liked-filter.md).
// 한도만큼 북마크를 넣어 두고 북마크가 새로 생기는 경로마다 거절되는지, 이미 북마크한 글·해제는 그대로 되는지 확인한다.
// 글 등록 경로(PostInteractionAdapter.bookmarkNewPost)는 PostInteractionPortIntegrationTest가 본다.
@PostgresIntegrationTest
@Import(
    InteractionService::class,
    BookmarkFolderService::class,
    BookmarkedPostFinder::class,
    PostSearchFlow::class,
    PostCandidateService::class,
    PostLookupService::class,
    PostResponseAssembler::class,
    PostInteractionAdapter::class,
    CommentCountAdapter::class,
    CategoryService::class,
    MemberService::class,
)
class BookmarkLimitIntegrationTest {

    @Autowired private lateinit var interactionService: InteractionService

    @Autowired private lateinit var bookmarkFolderService: BookmarkFolderService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var geminiService: GeminiService

    private val me = UUID.randomUUID()
    private val writer = UUID.randomUUID()
    private val folder = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        listOf(me to "me", writer to "writer").forEach { (id, nickname) ->
            jdbc.update("INSERT INTO members (id, nickname, email_verified) VALUES (?, ?, true)", id, nickname)
        }
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'A')", folder, me)
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private) " +
                "SELECT gen_random_uuid(), ?, 'https://example.com/' || g, 'kotlin 글 ' || g, false FROM generate_series(1, ?) g",
            writer,
            LIMIT,
        )
        jdbc.update("INSERT INTO bookmarks (user_id, post_id, created_at) SELECT ?, id, now() FROM posts WHERE user_id = ?", me, writer)
    }

    @Test
    fun `한도만큼 북마크한 회원은 새 글을 북마크할 수 없다`() {
        val newPost = insertPost()

        assertThrows(BookmarkLimitExceededException::class.java) { interactionService.setBookmark(newPost, me, bookmarked = true) }

        assertEquals(LIMIT, bookmarkCount())
    }

    @Test
    fun `한도에 닿아 있어도 이미 북마크한 글의 저장과 폴더 담기는 된다`() {
        val bookmarked = anyBookmarkedPost()

        assertTrue(interactionService.setBookmark(bookmarked, me, bookmarked = true).isBookmarked)
        assertEquals(listOf(folder), interactionService.addBookmarkFolder(bookmarked, folder, me).folderIds)
        assertEquals(1, bookmarkFolderService.batchAddBookmarksToFolder(me, folder, listOf(bookmarked)))
        assertEquals(LIMIT, bookmarkCount())
    }

    @Test
    fun `한도에 닿아 있으면 폴더 담기로도 새 북마크가 생기지 않는다`() {
        val newPost = insertPost()

        assertThrows(BookmarkLimitExceededException::class.java) { interactionService.addBookmarkFolder(newPost, folder, me) }

        assertEquals(LIMIT, bookmarkCount())
        assertEquals(0, folderItemCount())
    }

    @Test
    fun `일괄 담기는 새로 생길 북마크를 더해 한도를 넘으면 묶음 전체를 넣지 않는다`() {
        val bookmarked = anyBookmarkedPost()
        val newPost = insertPost()

        assertThrows(BookmarkLimitExceededException::class.java) {
            bookmarkFolderService.batchAddBookmarksToFolder(me, folder, listOf(bookmarked, newPost))
        }

        assertEquals(LIMIT, bookmarkCount())
        assertEquals(0, folderItemCount())
    }

    @Test
    fun `북마크를 하나 지우면 다시 하나를 저장할 수 있고 그다음은 다시 막힌다`() {
        interactionService.setBookmark(anyBookmarkedPost(), me, bookmarked = false)
        assertEquals(LIMIT - 1, bookmarkCount())

        // 자리가 하나인데 새 글 둘을 한꺼번에 담으면 둘 다 들어가지 않는다
        assertThrows(BookmarkLimitExceededException::class.java) {
            bookmarkFolderService.batchAddBookmarksToFolder(me, folder, listOf(insertPost(), insertPost()))
        }
        assertEquals(LIMIT - 1, bookmarkCount())

        assertTrue(interactionService.setBookmark(insertPost(), me, bookmarked = true).isBookmarked)
        assertEquals(LIMIT, bookmarkCount())

        assertThrows(BookmarkLimitExceededException::class.java) { interactionService.setBookmark(insertPost(), me, bookmarked = true) }
    }

    @Test
    fun `남의 북마크 수는 내 한도에 들지 않는다`() {
        val newPost = insertPost()

        assertTrue(interactionService.setBookmark(newPost, writer, bookmarked = true).isBookmarked)
    }

    private fun insertPost(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com/new', '새 글', false)", id, writer)

        return id
    }

    private fun anyBookmarkedPost(): UUID = jdbc.queryForObject("SELECT post_id FROM bookmarks WHERE user_id = ? LIMIT 1", UUID::class.java, me)!!

    private fun bookmarkCount(): Int = jdbc.queryForObject("SELECT count(*) FROM bookmarks WHERE user_id = ?", Int::class.java, me)!!

    private fun folderItemCount(): Int = jdbc.queryForObject("SELECT count(*) FROM bookmark_folder_items WHERE user_id = ?", Int::class.java, me)!!

    private companion object {
        // 기대값은 요구(회원당 10,000개)에서 온다 - 상수를 바꾸면 이 테스트가 실패해야 한다
        const val LIMIT = 10_000
    }
}
