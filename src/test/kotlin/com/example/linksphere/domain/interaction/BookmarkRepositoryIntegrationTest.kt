package com.example.linksphere.domain.interaction

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.util.UUID
import javax.sql.DataSource

@PostgresIntegrationTest
class BookmarkRepositoryIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var postgres: PostgreSQLContainer<*>

    @Autowired private lateinit var bookmarkRepository: BookmarkRepository

    @Autowired private lateinit var folderItemRepository: BookmarkFolderItemRepository

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val postId = UUID.randomUUID()
    private val otherPostId = UUID.randomUUID()
    private val folderA = UUID.randomUUID()
    private val folderB = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        insertMember(userId)
        insertMember(otherUserId)
        insertPost(postId)
        insertPost(otherPostId)
        insertFolder(folderA, "A")
        insertFolder(folderB, "B")
    }

    // application-secret.yml(로컬)에는 운영 datasource가 있다 — 테스트가 그쪽에 쓰지 않는다는 안전장치
    @Test
    fun `운영 DB가 아니라 테스트 컨테이너에 붙는다`() {
        val url = dataSource.connection.use { it.metaData.url }

        assertTrue(url.startsWith(postgres.jdbcUrl.substringBefore('?')), "datasource url = $url")
    }

    @Test
    fun `deleteIfNoFolderItems 는 남은 소속이 없을 때만 북마크를 지운다`() {
        bookmarkRepository.insertIgnoreConflict(userId, postId)

        assertEquals(1, bookmarkRepository.deleteIfNoFolderItems(userId, postId))
        assertEquals(0, countBookmarks(userId, postId))
    }

    // 다른 기기가 그사이 다른 폴더에 추가해 둔 경우 — 북마크와 그 소속을 지킨다(BE #89)
    @Test
    fun `deleteIfNoFolderItems 는 다른 소속이 남아 있으면 지우지 않는다`() {
        bookmarkRepository.insertIgnoreConflict(userId, postId)
        folderItemRepository.insertIgnoreConflict(userId, postId, folderB)

        assertEquals(0, bookmarkRepository.deleteIfNoFolderItems(userId, postId))
        assertEquals(1, countBookmarks(userId, postId))
        assertEquals(listOf(folderB), folderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
    }

    @Test
    fun `deleteIfNoFolderItems 는 북마크가 없으면 0행이고 다른 사용자의 북마크는 건드리지 않는다`() {
        bookmarkRepository.insertIgnoreConflict(otherUserId, postId)

        assertEquals(0, bookmarkRepository.deleteIfNoFolderItems(userId, postId))
        assertEquals(1, countBookmarks(otherUserId, postId))
    }

    @Test
    fun `insertIgnoreConflict 는 두 번 불러도 북마크·소속이 하나다`() {
        assertEquals(1, bookmarkRepository.insertIgnoreConflict(userId, postId))
        assertEquals(0, bookmarkRepository.insertIgnoreConflict(userId, postId))
        assertEquals(1, folderItemRepository.insertIgnoreConflict(userId, postId, folderA))
        assertEquals(0, folderItemRepository.insertIgnoreConflict(userId, postId, folderA))

        assertEquals(1, countBookmarks(userId, postId))
        assertEquals(listOf(folderA), folderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId))
    }

    // 소속은 bookmarks에 FK ON DELETE CASCADE로 묶여 있다(fk_bookmark_folder_items_bookmark)
    @Test
    fun `북마크를 지우면 그 소속도 함께 지워진다`() {
        bookmarkRepository.insertIgnoreConflict(userId, postId)
        folderItemRepository.insertIgnoreConflict(userId, postId, folderA)
        folderItemRepository.insertIgnoreConflict(userId, postId, folderB)

        assertEquals(1, bookmarkRepository.deleteByUserIdAndPostId(userId, postId))
        assertTrue(folderItemRepository.findFolderIdsByUserIdAndPostId(userId, postId).isEmpty())
    }

    @Test
    fun `일괄 DELETE 는 지울 행이 없어도 예외 없이 0을 돌려준다`() {
        assertEquals(0, bookmarkRepository.deleteByUserIdAndPostId(userId, postId))
        assertEquals(0, folderItemRepository.deleteByUserIdAndPostIdAndFolderId(userId, postId, folderA))
        assertEquals(0, folderItemRepository.deleteByUserIdAndPostId(userId, postId))
    }

    @Test
    fun `countUncategorizedByUserId 는 소속이 하나도 없는 북마크만 센다`() {
        bookmarkRepository.insertIgnoreConflict(userId, postId)
        bookmarkRepository.insertIgnoreConflict(userId, otherPostId)
        folderItemRepository.insertIgnoreConflict(userId, otherPostId, folderA)
        bookmarkRepository.insertIgnoreConflict(otherUserId, postId)

        assertEquals(1, bookmarkRepository.countUncategorizedByUserId(userId))
    }

    private fun insertMember(id: UUID) {
        jdbc.update("INSERT INTO members (id) VALUES (?)", id)
    }

    private fun insertPost(id: UUID) {
        jdbc.update("INSERT INTO posts (id, user_id, url, title) VALUES (?, ?, 'https://example.com', 't')", id, userId)
    }

    private fun insertFolder(id: UUID, name: String) {
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, ?)", id, userId, name)
    }

    private fun countBookmarks(user: UUID, post: UUID): Int = jdbc.queryForObject(
        "SELECT COUNT(*) FROM bookmarks WHERE user_id = ? AND post_id = ?",
        Int::class.java,
        user,
        post,
    )!!
}
