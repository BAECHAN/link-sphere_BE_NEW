package com.example.linksphere.architecture

import com.example.linksphere.domain.auth.AccessTokenCheck
import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.BookmarkFolderService
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.post.PostResponseAssembler
import com.example.linksphere.domain.post.PostService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import jakarta.persistence.EntityManager
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * 조회 한 번이 DB에 보내는 SQL 문 수를 고정한다(docs/plans/2026-11-16-be-structure-stage2.md PR-1).
 *
 * 2단계는 "한 문장에 다른 기능의 테이블을 묶어 읽던 것"을 "그 기능에 물어 id를 받은 뒤 다시 읽는 것"으로 바꾼다. 그러면
 * 문장 수가 늘 수 있는데, 응답은 그대로라 다른 테스트로는 보이지 않는다. 이 테스트가 실패하면 문장 수가 바뀐 것이다 -
 * 의도한 변화면 숫자를 고치고 PR 본문에 전후를 적는다. 글 수에 비례해 느는 조회(N+1)도 여기서 걸린다.
 *
 * 픽스처는 JdbcTemplate으로 넣는다 - 엔티티로 저장하면 영속성 컨텍스트에 남아 조회가 DB를 건너뛴다.
 * 세는 값은 Hibernate가 준비한 문장 수(prepareStatementCount)라 JdbcTemplate이 보낸 문장은 들지 않는다.
 */
@PostgresIntegrationTest
@Import(
    PostService::class,
    PostResponseAssembler::class,
    CommentService::class,
    BookmarkFolderService::class,
    CategoryService::class,
    MemberSessionService::class,
)
@TestPropertySource(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
class QueryCountBaselineIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var commentService: CommentService

    @Autowired private lateinit var bookmarkFolderService: BookmarkFolderService

    @Autowired private lateinit var memberSessionService: MemberSessionService

    @Autowired private lateinit var entityManager: EntityManager

    @Autowired private lateinit var jdbc: JdbcTemplate

    // DB를 쓰지 않는 바깥 연결(크롤링·AI·스토리지)과 별도 트랜잭션으로 커밋하는 것들은 조회 경로에서 불리지 않는다
    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    private val writer = UUID.randomUUID()
    private val bot = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val folder = UUID.randomUUID()
    private lateinit var posts: List<UUID>

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'writer')", writer)
        jdbc.update("INSERT INTO members (id, nickname, is_bot) VALUES (?, 'feedbot', true)", bot)
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'viewer')", viewer)
        jdbc.update("INSERT INTO categories (id, name, slug) VALUES (9001, 'Frontend', 'frontend'), (9002, 'Backend', 'backend')")

        // 글 수에 비례해 문장이 늘면(N+1) 드러나도록 작성자·카테고리·댓글·좋아요·북마크가 서로 다른 글을 여러 개 둔다
        posts =
            listOf(
                insertPost("React post", writer, categoryIds = listOf(9001L)),
                insertPost("Kotlin post", writer, categoryIds = listOf(9001L, 9002L)),
                insertPost("Plain post", writer),
                insertPost("Bot post", bot, categoryIds = listOf(9002L)),
            )
        val first = posts[0]
        val second = posts[1]

        val root = insertComment(first, viewer, parent = null)
        insertComment(first, writer, parent = root)
        insertComment(second, viewer, parent = null)
        jdbc.update("INSERT INTO comment_reactions (user_id, comment_id) VALUES (?, ?)", writer, root)

        jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", viewer, first)
        jdbc.update("INSERT INTO bookmarks (user_id, post_id, created_at) VALUES (?, ?, now() - interval '2 hours')", viewer, first)
        jdbc.update("INSERT INTO bookmarks (user_id, post_id, created_at) VALUES (?, ?, now() - interval '1 hour')", viewer, second)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'A')", folder, viewer)
        jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", viewer, first, folder)

        jdbc.update(
            "INSERT INTO member_sessions (id, member_id, access_token_hash, refresh_token_hash, access_expires_at, refresh_expires_at, family_id) " +
                "VALUES (?, ?, ?, ?, now() + interval '1 hour', now() + interval '1 day', ?)",
            UUID.randomUUID(),
            viewer,
            SecureToken.hash(ACCESS_TOKEN),
            SecureToken.hash("refresh"),
            UUID.randomUUID(),
        )
    }

    @Test
    fun `피드 목록 - 비로그인`() {
        val page = countingQueries(FEED_ANONYMOUS) { feed(userId = null) }

        assertEquals(4, page.content.size)
    }

    @Test
    fun `피드 목록 - 로그인`() {
        val page = countingQueries(FEED_LOGGED_IN) { feed() }

        assertEquals(4, page.content.size)
    }

    @Test
    fun `피드 목록 - 내가 북마크한 글만`() {
        val page = countingQueries(FEED_LOGGED_IN) { feed(filter = "isBookmarked") }

        assertEquals(2, page.content.size)
    }

    @Test
    fun `피드 목록 - 내가 좋아요한 글만`() {
        val page = countingQueries(FEED_LOGGED_IN) { feed(filter = "isLiked") }

        assertEquals(1, page.content.size)
    }

    @Test
    fun `피드 목록 - 닉네임으로 거르기`() {
        val page = countingQueries(FEED_LOGGED_IN) { feed(nickname = "writ") }

        assertEquals(3, page.content.size)
    }

    @Test
    fun `피드 목록 - 봇 글 빼기`() {
        val page = countingQueries(FEED_LOGGED_IN) { feed(filter = "excludeBots") }

        assertEquals(3, page.content.size)
    }

    @Test
    fun `피드 목록 - 카테고리로 거르기`() {
        val page = countingQueries(FEED_CATEGORY_FILTER) { feed(category = "backend") }

        assertEquals(2, page.content.size)
    }

    @Test
    fun `피드 목록 - 검색`() {
        val page = countingQueries(FEED_SEARCH) { feed(search = "post") }

        assertEquals(4, page.content.size)
    }

    @Test
    fun `글 상세`() {
        val post = countingQueries(POST_DETAIL) { postService.getPostById(posts[1], viewer) }

        assertEquals(2, post.categories.size)
    }

    @Test
    fun `댓글 목록`() {
        val comments = countingQueries(COMMENT_LIST) { commentService.getComments(posts[0], viewer) }

        assertEquals(1, comments.size)
        assertEquals(1, comments[0].replies.size)
    }

    @Test
    fun `내 댓글 목록`() {
        val page = countingQueries(MY_COMMENTS) { commentService.getMyCommentsByCursor(viewer, null, 20) }

        assertEquals(2, page.content.size)
    }

    @Test
    fun `북마크 목록 - 전체, 최신순`() {
        val page = countingQueries(BOOKMARK_LIST) { bookmarkFolderService.getBookmarkedPostsByCursor(viewer, "all", null, null, null, 20) }

        assertEquals(2, page.content.size)
    }

    @Test
    fun `북마크 목록 - 폴더 안, 최신순`() {
        val page =
            countingQueries(BOOKMARK_LIST_IN_FOLDER) {
                bookmarkFolderService.getBookmarkedPostsByCursor(viewer, folder.toString(), null, null, null, 20)
            }

        assertEquals(1, page.content.size)
    }

    @Test
    fun `북마크 목록 - 검색`() {
        val page = countingQueries(BOOKMARK_SEARCH) { bookmarkFolderService.getBookmarkedPostsByCursor(viewer, "all", null, "post", null, 20) }

        assertEquals(2, page.content.size)
    }

    @Test
    fun `북마크 목록 - 폴더 안, 검색`() {
        val page =
            countingQueries(BOOKMARK_SEARCH_IN_FOLDER) {
                bookmarkFolderService.getBookmarkedPostsByCursor(viewer, folder.toString(), null, "post", null, 20)
            }

        assertEquals(1, page.content.size)
    }

    @Test
    fun `로그인 확인 - 요청마다 access 토큰을 판정한다`() {
        val check = countingQueries(SESSION_CHECK) { memberSessionService.checkAccessToken(ACCESS_TOKEN) }

        assertTrue(check is AccessTokenCheck.Valid)
    }

    private fun feed(
        category: String? = null,
        search: String? = null,
        filter: String? = null,
        nickname: String? = null,
        userId: UUID? = viewer,
    ) = postService.getPostsByCursor(category, search, filter, nickname, null, 20, userId)

    private fun <T> countingQueries(expected: Long, block: () -> T): T {
        val statistics = entityManager.entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        entityManager.clear()
        statistics.clear()

        val result = block()

        assertEquals(expected, statistics.prepareStatementCount, "이 조회가 DB에 보낸 SQL 문 수")
        return result
    }

    private fun insertPost(title: String, owner: UUID, categoryIds: List<Long> = emptyList()): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', ?, false)", id, owner, title)
        categoryIds.forEach { jdbc.update("INSERT INTO post_categories (post_id, category_id) VALUES (?, ?)", id, it) }
        return id
    }

    private fun insertComment(post: UUID, author: UUID, parent: UUID?): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO comments (id, post_id, user_id, parent_id, content) VALUES (?, ?, ?, ?, 'c')", id, post, author, parent)
        return id
    }

    // 2026-11-16 실측값. 2단계 PR이 이 숫자를 바꾸면 그 PR 본문에 전후를 적는다
    companion object {
        private const val ACCESS_TOKEN = "access-token-for-query-count"

        // PR-4(글의 카테고리를 ID 집합으로): 글을 그리는 조회마다 +1 - 글과 카테고리 이름을 한 문장으로 묶어 읽던 것이
        // "연결 테이블에서 id 읽기"와 "category에 이름 묻기"로 나뉘었다. 카테고리로 거를 때는 이름·slug를 id로 푸는 문장이 하나 더 든다
        private const val FEED_ANONYMOUS = 8L
        private const val FEED_LOGGED_IN = 11L
        private const val FEED_CATEGORY_FILTER = 12L
        private const val FEED_SEARCH = 12L
        private const val POST_DETAIL = 10L
        private const val COMMENT_LIST = 4L
        private const val MY_COMMENTS = 1L
        private const val BOOKMARK_LIST = 11L
        private const val BOOKMARK_LIST_IN_FOLDER = 12L
        private const val BOOKMARK_SEARCH = 12L
        private const val BOOKMARK_SEARCH_IN_FOLDER = 13L
        private const val SESSION_CHECK = 1L
    }
}
