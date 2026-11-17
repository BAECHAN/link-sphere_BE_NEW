package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentCountAdapter
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.post.PostCandidateService
import com.example.linksphere.domain.post.PostLookupService
import com.example.linksphere.domain.post.PostResponseAssembler
import com.example.linksphere.domain.post.PostSearchFlow
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

// 북마크 목록을 서비스부터 응답까지 실제 Postgres에서 읽는다(docs/plans/2026-11-16-be-structure-stage2.md PR-9c). 북마크 테이블은
// interaction이, 공개 여부·검색·글 쪽 정렬 키는 post가 읽게 나뉘었다 - 쪽을 넘겨 끝까지 읽은 순서가 예전의 한 문장
// (bookmarks와 posts를 조인해 [첫 키, 북마크 시각, 글 id]로 정렬)과 같은지를 그 문장을 SQL로 직접 써서 견준다.
@PostgresIntegrationTest
@Import(
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
class BookmarkedPostsByCursorIntegrationTest {

    @Autowired private lateinit var bookmarkFolderService: BookmarkFolderService

    @Autowired private lateinit var jdbc: JdbcTemplate

    // 임베딩은 null을 돌려준다(mock 기본값) - 키워드 검색으로 읽는다
    @MockitoBean private lateinit var geminiService: GeminiService

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()
    private val folder = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        listOf(me to "me", other to "other").forEach { (id, nickname) ->
            jdbc.update("INSERT INTO members (id, nickname, email_verified) VALUES (?, ?, true)", id, nickname)
        }
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'A')", folder, me)

        // 같은 시각에 한꺼번에 북마크한 글(일괄 추가) - 글 id로만 순서가 갈린다. 제목도 같아 제목순의 동점이 쪽 경계에 걸린다
        repeat(5) { bookmark(insertPost("kotlin 같은 제목"), TIED, inFolder = it % 2 == 0) }
        // 시각이 다르고 열람 기록이 있는 글. 검색에 걸리는 것과 안 걸리는 것을 섞는다
        repeat(6) {
            val post = insertPost(if (it % 2 == 0) "kotlin 글 $it" else "다른 글 $it")
            bookmark(post, "2026-03-0${it + 2} 00:00:00.000001+09", inFolder = it < 4)
            view(post, "2026-03-1${it / 2} 00:00:00.123456")
        }
        // 열람 기록이 없는 글 - 최근 열람순에서 모두 같은 키로 맨 뒤에 모인다
        repeat(4) { bookmark(insertPost("kotlin 안 본 글 $it"), if (it < 2) TIED else "2026-02-0${it + 1} 00:00:00+09", inFolder = true) }
        // 내 비공개 글은 나오고, 북마크한 뒤 비공개로 바뀐 남의 글은 빠진다
        bookmark(insertPost("kotlin 내 비공개 글", owner = me, isPrivate = true), "2026-03-20 00:00:00+09", inFolder = true)
        repeat(3) { bookmark(insertPost("kotlin 비공개로 바뀐 글 $it", isPrivate = true), if (it == 0) TIED else "2026-03-2${it + 1} 00:00:00+09", inFolder = true) }
        // 남의 북마크는 섞이지 않는다
        jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", other, insertPost("kotlin 남이 북마크한 글"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["all", "folder"])
    fun `검색 없이 정렬 4종을 쪽을 넘겨 끝까지 읽으면 한 문장으로 정렬한 순서와 같다`(scope: String) {
        ORDER_BY.forEach { (sort, order) ->
            val expected = expectedIds(scope, order, search = false)

            assertTrue(expected.size >= 8, "$sort ${expected.size}")
            assertEquals(expected, walk(scope, sort, search = null), "$scope $sort")
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["all", "folder"])
    fun `검색하면서 오래된순·제목순·열람순으로 끝까지 읽어도 한 문장으로 정렬한 순서와 같다`(scope: String) {
        ORDER_BY.filterKeys { it != "latest" }.forEach { (sort, order) ->
            val expected = expectedIds(scope, order, search = true)

            assertTrue(expected.size >= 6, "$sort ${expected.size}")
            assertEquals(expected, walk(scope, sort, search = "kotlin"), "$scope $sort")
        }
    }

    // 검색 중 기본 정렬은 관련도순이다. 점수 식을 SQL로 다시 쓰지 않고, 한 번에 받은 순서와 쪽을 넘긴 순서가 같은지와 걸린 글의 집합을 본다
    @ParameterizedTest
    @ValueSource(strings = ["all", "folder"])
    fun `검색 기본 정렬(관련도순)은 쪽을 넘겨도 한 번에 받은 순서와 같고 검색에 걸린 글만 나온다`(scope: String) {
        val atOnce = bookmarkFolderService.getBookmarkedPostsByCursor(me, folderKey(scope), null, "kotlin", null, 50)

        assertEquals(null, atOnce.nextCursor)
        assertEquals(atOnce.content.map { it.id }, walk(scope, null, search = "kotlin"))
        assertEquals(expectedIds(scope, "b.post_id", search = true).toSet(), atOnce.content.map { it.id }.toSet())
    }

    // 최신순은 북마크 테이블을 먼저 읽고 보이는 글만 남긴다. 보이지 않는 글이 한 번에 읽는 양보다 많이 이어져도 쪽을 채워야 한다
    @Test
    fun `맨 앞에 비공개로 바뀐 글이 많이 몰려 있어도 최신순 첫 쪽이 차고 끝까지 읽는다`() {
        repeat(60) { bookmark(insertPost("비공개로 바뀐 최근 글 $it", isPrivate = true), "2026-04-01 00:00:${it.toString().padStart(2, '0')}+09", inFolder = false) }
        val expected = expectedIds("all", ORDER_BY.getValue("latest"), search = false)

        val first = bookmarkFolderService.getBookmarkedPostsByCursor(me, "all", "latest", null, null, 3)

        assertEquals(expected.take(3), first.content.map { it.id })
        assertEquals(expected, walk("all", "latest", search = null))
    }

    @Test
    fun `북마크가 없는 폴더는 어느 정렬이든 빈 쪽이다`() {
        val empty = UUID.randomUUID()
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'B')", empty, me)

        listOf("latest", "oldest", "title", "viewed").forEach { sort ->
            listOf(null, "kotlin").forEach { search ->
                val page = bookmarkFolderService.getBookmarkedPostsByCursor(me, empty.toString(), sort, search, null, 3)

                assertEquals(emptyList<UUID>(), page.content.map { it.id }, "$sort $search")
                assertEquals(null, page.nextCursor, "$sort $search")
            }
        }
    }

    private fun folderKey(scope: String) = if (scope == "folder") folder.toString() else "all"

    private fun walk(scope: String, sort: String?, search: String?, size: Int = 3): List<UUID> {
        val ids = mutableListOf<UUID>()
        var cursor: String? = null

        do {
            val page = bookmarkFolderService.getBookmarkedPostsByCursor(me, folderKey(scope), sort, search, cursor, size)
            ids += page.content.map { it.id }
            cursor = page.nextCursor
            // cursor가 앞으로 나아가지 않으면 끝없이 돈다 - 그때는 멈추지 않는 대신 실패하게 한다
            check(ids.size <= MAX_WALKED_ROWS) { "끝나지 않는 cursor: $scope $sort $search" }
        } while (cursor != null)

        return ids
    }

    // PR-9c 전의 북마크 목록 쿼리와 같은 조건·정렬이다
    private fun expectedIds(scope: String, order: String, search: Boolean): List<UUID> = jdbc.queryForList(
        "SELECT b.post_id FROM bookmarks b JOIN posts p ON p.id = b.post_id " +
            "LEFT JOIN post_views v ON v.post_id = b.post_id AND v.user_id = b.user_id " +
            "WHERE b.user_id = ? AND (p.is_private = false OR p.user_id = ?) " +
            (if (scope == "folder") "AND EXISTS (SELECT 1 FROM bookmark_folder_items i WHERE i.user_id = b.user_id AND i.post_id = b.post_id AND i.folder_id = '$folder') " else "") +
            (if (search) "AND p.title LIKE '%kotlin%' " else "") +
            "ORDER BY $order",
        UUID::class.java,
        me,
        me,
    )

    private fun insertPost(title: String, owner: UUID = other, isPrivate: Boolean = false): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', ?, ?)", id, owner, title, isPrivate)

        return id
    }

    private fun bookmark(post: UUID, createdAt: String, inFolder: Boolean) {
        jdbc.update("INSERT INTO bookmarks (user_id, post_id, created_at) VALUES (?, ?, CAST(? AS timestamptz))", me, post, createdAt)

        if (inFolder) {
            jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", me, post, folder)
        }
    }

    private fun view(post: UUID, viewedAt: String) {
        jdbc.update("INSERT INTO post_views (user_id, post_id, viewed_at) VALUES (?, ?, CAST(? AS timestamp))", me, post, viewedAt)
    }

    private companion object {
        const val TIED = "2026-03-01 00:00:00.654321+09"
        const val MAX_WALKED_ROWS = 500

        val ORDER_BY =
            mapOf(
                "latest" to "b.created_at DESC, b.post_id DESC",
                "oldest" to "b.created_at ASC, b.post_id ASC",
                "title" to "left(p.title, 200) ASC, b.created_at DESC, b.post_id DESC",
                "viewed" to "coalesce(v.viewed_at, TIMESTAMP '1970-01-01') DESC, b.created_at DESC, b.post_id DESC",
            )
    }
}
