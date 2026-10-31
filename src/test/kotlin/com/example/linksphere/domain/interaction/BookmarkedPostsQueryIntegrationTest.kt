package com.example.linksphere.domain.interaction

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// 북마크 목록 조회(BookmarkRepositoryImpl.findBookmarkedPosts)를 실제 Postgres에서 검증한다 — Criteria API로 만든
// 폴더 EXISTS 세미조인·가시성·정렬·검색 조건은 서비스 단위 테스트가 저장소를 목으로 바꿔 한 번도 실행되지 않았다
// (2026-10-31 JaCoCo 1회 측정에서 이 메서드 90줄 중 1줄만 실행됨, claude-base docs/METRICS.md)
@PostgresIntegrationTest
class BookmarkedPostsQueryIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var bookmarkRepository: BookmarkRepository

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val folderA = UUID.randomUUID()
    private val folderB = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", userId)
        jdbc.update("INSERT INTO members (id) VALUES (?)", otherUserId)
        insertFolder(folderA, "A")
        insertFolder(folderB, "B")
    }

    @Test
    fun `전체 보기는 내 북마크만 최근 북마크순으로 돌려준다`() {
        val older = insertPost("older")
        val newer = insertPost("newer")
        bookmark(userId, older, hoursAgo = 2)
        bookmark(userId, newer, hoursAgo = 1)
        bookmark(otherUserId, insertPost("other's"), hoursAgo = 0)

        val page = find()

        assertEquals(listOf(newer, older), page.content.map { it.id })
        assertEquals(2, page.totalElements)
    }

    @Test
    fun `폴더 보기는 그 폴더 소속만, 미분류 보기는 소속이 하나도 없는 북마크만 돌려준다`() {
        val inA = insertPost("in A")
        val inAandB = insertPost("in A and B")
        val uncategorized = insertPost("uncategorized")
        bookmark(userId, inA, hoursAgo = 3)
        bookmark(userId, inAandB, hoursAgo = 2)
        bookmark(userId, uncategorized, hoursAgo = 1)
        addToFolder(inA, folderA)
        addToFolder(inAandB, folderA)
        addToFolder(inAandB, folderB)

        val folderPage = find(folderId = folderA)
        val uncategorizedPage = find(onlyUncategorized = true)

        // 두 폴더에 든 글도 EXISTS 세미조인이라 한 번만 나온다
        assertEquals(listOf(inAandB, inA), folderPage.content.map { it.id })
        assertEquals(2, folderPage.totalElements)
        assertEquals(listOf(uncategorized), uncategorizedPage.content.map { it.id })
        assertEquals(1, uncategorizedPage.totalElements)
    }

    @Test
    fun `남의 비공개 글은 북마크해 뒀어도 빠지고 내 비공개 글은 나온다`() {
        val othersPrivate = insertPost("others private", owner = otherUserId, isPrivate = true)
        val myPrivate = insertPost("my private", isPrivate = true)
        val othersPublic = insertPost("others public", owner = otherUserId)
        bookmark(userId, othersPrivate, hoursAgo = 3)
        bookmark(userId, myPrivate, hoursAgo = 2)
        bookmark(userId, othersPublic, hoursAgo = 1)

        val page = find()

        assertEquals(listOf(othersPublic, myPrivate), page.content.map { it.id })
        assertEquals(2, page.totalElements)
    }

    @Test
    fun `정렬은 oldest·title·views가 각각의 기준을 따른다`() {
        val b = insertPost("b title", viewCount = 5)
        val a = insertPost("a title", viewCount = 1)
        val c = insertPost("c title", viewCount = 9)
        bookmark(userId, b, hoursAgo = 3)
        bookmark(userId, a, hoursAgo = 2)
        bookmark(userId, c, hoursAgo = 1)

        assertEquals(listOf(b, a, c), find(sort = "oldest").content.map { it.id })
        assertEquals(listOf(a, b, c), find(sort = "title").content.map { it.id })
        assertEquals(listOf(c, b, a), find(sort = "views").content.map { it.id })
    }

    @Test
    fun `최근 본 순은 내가 본 시각순이고 안 본 글은 최근 북마크순으로 뒤에 온다`() {
        val viewedLongAgo = insertPost("viewed long ago")
        val viewedRecently = insertPost("viewed recently")
        val neverViewedOld = insertPost("never viewed, old bookmark")
        val neverViewedNew = insertPost("never viewed, new bookmark")
        bookmark(userId, viewedLongAgo, hoursAgo = 4)
        bookmark(userId, viewedRecently, hoursAgo = 3)
        bookmark(userId, neverViewedOld, hoursAgo = 2)
        bookmark(userId, neverViewedNew, hoursAgo = 1)
        view(userId, viewedLongAgo, hoursAgo = 10)
        view(userId, viewedRecently, hoursAgo = 1)
        // 다른 사용자가 본 기록은 내 정렬에 영향을 주지 않는다
        view(otherUserId, neverViewedOld, hoursAgo = 0)

        val page = find(sort = "viewed")

        assertEquals(listOf(viewedRecently, viewedLongAgo, neverViewedNew, neverViewedOld), page.content.map { it.id })
    }

    @Test
    fun `검색어는 제목에 걸린 글만 남기고 총 건수도 같은 조건으로 센다`() {
        val match = insertPost("React 상태관리")
        val noMatch = insertPost("Kotlin 코루틴")
        bookmark(userId, match, hoursAgo = 2)
        bookmark(userId, noMatch, hoursAgo = 1)

        val page = find(search = "react")

        assertEquals(listOf(match), page.content.map { it.id })
        assertEquals(1, page.totalElements)
    }

    @Test
    fun `검색어 임베딩이 가까운 글은 키워드가 안 맞아도 걸린다`() {
        val semantic = insertPost("전혀 다른 제목", embedding = unitVector(0))
        val far = insertPost("또 다른 제목", embedding = unitVector(1))
        bookmark(userId, semantic, hoursAgo = 2)
        bookmark(userId, far, hoursAgo = 1)

        val page = find(search = "react", queryEmbedding = unitVector(0).toFloatArray())

        assertEquals(listOf(semantic), page.content.map { it.id })
        assertEquals(1, page.totalElements)
    }

    @Test
    fun `페이지를 나눠도 총 건수는 전체 건수이고 두 페이지가 겹치지 않는다`() {
        val ids = (1..3).map { hoursAgo -> insertPost("post $hoursAgo").also { bookmark(userId, it, hoursAgo) } }

        val first = find(pageable = PageRequest.of(0, 2))
        val second = find(pageable = PageRequest.of(1, 2))

        assertEquals(listOf(ids[0], ids[1]), first.content.map { it.id })
        assertEquals(listOf(ids[2]), second.content.map { it.id })
        assertEquals(3, first.totalElements)
        assertEquals(3, second.totalElements)
    }

    @Test
    fun `북마크가 없으면 빈 페이지와 총 0건을 돌려준다`() {
        val page = find(folderId = folderA)

        assertTrue(page.content.isEmpty())
        assertEquals(0, page.totalElements)
    }

    private fun find(
        folderId: UUID? = null,
        onlyUncategorized: Boolean = false,
        sort: String = "latest",
        search: String? = null,
        pageable: PageRequest = PageRequest.of(0, 20),
        queryEmbedding: FloatArray? = null,
    ) = bookmarkRepository.findBookmarkedPosts(userId, folderId, onlyUncategorized, sort, search, pageable, queryEmbedding)

    private fun insertPost(
        title: String,
        owner: UUID = userId,
        isPrivate: Boolean = false,
        viewCount: Int = 0,
        embedding: List<Float>? = null,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private, view_count, embedding) " +
                "VALUES (?, ?, 'https://example.com', ?, ?, ?, CAST(? AS extensions.vector))",
            id,
            owner,
            title,
            isPrivate,
            viewCount,
            embedding?.joinToString(",", "[", "]"),
        )
        return id
    }

    private fun insertFolder(id: UUID, name: String) {
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, ?)", id, userId, name)
    }

    private fun bookmark(user: UUID, post: UUID, hoursAgo: Int) {
        jdbc.update(
            "INSERT INTO bookmarks (user_id, post_id, created_at) VALUES (?, ?, now() - make_interval(hours => ?))",
            user,
            post,
            hoursAgo,
        )
    }

    private fun addToFolder(post: UUID, folder: UUID) {
        jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", userId, post, folder)
    }

    private fun view(user: UUID, post: UUID, hoursAgo: Int) {
        jdbc.update(
            "INSERT INTO post_views (user_id, post_id, viewed_at) VALUES (?, ?, now() - make_interval(hours => ?))",
            user,
            post,
            hoursAgo,
        )
    }

    // posts.embedding은 768차원이다. 서로 다른 축의 단위 벡터는 코사인 거리가 1이라 임계값(0.30) 밖이다
    private fun unitVector(axis: Int): List<Float> = List(768) { if (it == axis) 1f else 0f }
}
