package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentCountAdapter
import com.example.linksphere.domain.interaction.PostInteractionAdapter
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadata
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.exception.BookmarkFolderNotFoundException
import com.example.linksphere.global.exception.BookmarkLimitExceededException
import com.example.linksphere.global.exception.ForbiddenException
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * 글 응답의 좋아요·북마크와 글 등록 때의 북마크 저장을 실제 Postgres로 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-9a).
 *
 * post는 interaction의 저장소를 모르고 PostInteractionPort에 묻는다. 구현(PostInteractionAdapter)이 빈으로 연결돼 실제 SQL까지
 * 가는지, 그리고 글 등록 때의 북마크·폴더 소속이 FK를 지켜 실제로 저장되는지는 mock으로 보이지 않는다.
 */
@PostgresIntegrationTest
@Import(PostService::class, PostSearchFlow::class, PostResponseAssembler::class, PostInteractionAdapter::class, CommentCountAdapter::class, CategoryService::class, MemberService::class)
class PostInteractionPortIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    private val writer = UUID.randomUUID()
    private val viewer = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private val busyPost = UUID.randomUUID()
    private val quietPost = UUID.randomUUID()
    private val viewerFolderA = UUID.randomUUID()
    private val viewerFolderB = UUID.randomUUID()
    private val othersFolder = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        listOf(writer to "writer", viewer to "viewer", other to "other").forEach { (id, nickname) ->
            jdbc.update("INSERT INTO members (id, nickname, email_verified) VALUES (?, ?, true)", id, nickname)
        }
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private, created_at) VALUES (?, ?, 'https://example.com/busy', 'busy', false, now() - interval '1 hour')", busyPost, writer)
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private, created_at) VALUES (?, ?, 'https://example.com/quiet', 'quiet', false, now() - interval '2 hours')", quietPost, writer)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'A')", viewerFolderA, viewer)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'B')", viewerFolderB, viewer)
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'X')", othersFolder, other)

        // busyPost: 좋아요 2(viewer·other), 북마크 2(viewer는 폴더 A·B, other는 미분류)
        listOf(viewer, other).forEach {
            jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", it, busyPost)
            jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", it, busyPost)
        }
        listOf(viewerFolderA, viewerFolderB).forEach {
            jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", viewer, busyPost, it)
        }
    }

    @Test
    fun `글 목록은 글마다 좋아요·북마크 수와 내 상태를 싣는다`() {
        val page = postService.getPostsByCursor(null, null, null, null, null, 20, viewer).content.associateBy { it.id }

        val busy = page.getValue(busyPost)
        assertEquals(2, busy.stats.likeCount)
        assertEquals(2, busy.stats.bookmarkCount)
        assertEquals(true, busy.userInteractions.isLiked)
        assertEquals(true, busy.userInteractions.isBookmarked)
        assertEquals(setOf(viewerFolderA, viewerFolderB), busy.userInteractions.bookmarkFolderIds.toSet())

        val quiet = page.getValue(quietPost)
        assertEquals(0, quiet.stats.likeCount)
        assertEquals(0, quiet.stats.bookmarkCount)
        assertEquals(false, quiet.userInteractions.isLiked)
        assertEquals(false, quiet.userInteractions.isBookmarked)
        assertEquals(emptyList<UUID>(), quiet.userInteractions.bookmarkFolderIds)
    }

    // 남의 폴더 소속(other의 미분류 북마크, viewer의 폴더)이 다른 사람의 응답에 섞이지 않아야 한다
    @Test
    fun `글 목록의 내 상태는 보는 사람마다 다르고 비로그인은 모두 false다`() {
        val asWriter = postService.getPostsByCursor(null, null, null, null, null, 20, writer).content.first { it.id == busyPost }
        val asOther = postService.getPostsByCursor(null, null, null, null, null, 20, other).content.first { it.id == busyPost }
        val anonymous = postService.getPostsByCursor(null, null, null, null, null, 20, null).content.first { it.id == busyPost }

        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = false, bookmarkFolderIds = emptyList()), asWriter.userInteractions)
        assertEquals(PostUserInteractions(isLiked = true, isBookmarked = true, bookmarkFolderIds = emptyList()), asOther.userInteractions)
        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = false, bookmarkFolderIds = emptyList()), anonymous.userInteractions)
        assertEquals(2, anonymous.stats.likeCount)
        assertEquals(2, anonymous.stats.bookmarkCount)
    }

    @Test
    fun `글 상세도 같은 값을 싣는다`() {
        val detail = postService.getPostById(busyPost, viewer)
        val anonymous = postService.getPostById(busyPost, null)

        assertEquals(2, detail.stats.likeCount)
        assertEquals(2, detail.stats.bookmarkCount)
        assertEquals(true, detail.userInteractions.isLiked)
        assertEquals(true, detail.userInteractions.isBookmarked)
        assertEquals(setOf(viewerFolderA, viewerFolderB), detail.userInteractions.bookmarkFolderIds.toSet())
        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = false, bookmarkFolderIds = emptyList()), anonymous.userInteractions)
        assertEquals(2, anonymous.stats.bookmarkCount)
    }

    // filter=isLiked·isBookmarked는 post가 interaction에 글 id를 물어 IN으로 거른다(같은 계획 PR-9b) - 예전에는 글 쿼리의 서브쿼리였다
    @Test
    fun `isBookmarked 와 isLiked 필터는 내가 한 글만 돌려주고 함께 걸면 둘 다 한 글만 남는다`() {
        val bookmarkedOnly = insertPost("bookmarked-only")
        val likedOnly = insertPost("liked-only")
        jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", viewer, bookmarkedOnly)
        jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", viewer, likedOnly)

        assertEquals(setOf(busyPost, bookmarkedOnly), feedIds("isBookmarked", viewer))
        assertEquals(setOf(busyPost, likedOnly), feedIds("isLiked", viewer))
        assertEquals(setOf(busyPost), feedIds("isBookmarked,isLiked", viewer))
    }

    // 남의 북마크·좋아요가 내 필터에 섞이지 않고, 한 것이 없으면 전체 글이 아니라 빈 결과다
    @Test
    fun `북마크나 좋아요한 글이 없으면 필터 결과가 비고 남이 한 글은 나오지 않는다`() {
        assertEquals(emptySet<UUID>(), feedIds("isBookmarked", writer))
        assertEquals(emptySet<UUID>(), feedIds("isLiked", writer))
        assertEquals(setOf(busyPost), feedIds("isBookmarked", other))
    }

    // 좋아요·북마크했어도 남의 비공개 글은 필터 결과에 새지 않는다(공개 게이트가 함께 걸린다)
    @Test
    fun `북마크·좋아요한 뒤 비공개로 바뀐 남의 글은 필터 결과에서 빠진다`() {
        val turnedPrivate = insertPost("turned-private")
        jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", viewer, turnedPrivate)
        jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", viewer, turnedPrivate)
        jdbc.update("UPDATE posts SET is_private = true WHERE id = ?", turnedPrivate)

        assertEquals(setOf(busyPost), feedIds("isBookmarked", viewer))
        assertEquals(setOf(busyPost), feedIds("isLiked", viewer))
    }

    @Test
    fun `비로그인이면 isBookmarked 와 isLiked 를 무시하고 공개 글을 모두 돌려준다`() {
        assertEquals(setOf(busyPost, quietPost), feedIds("isBookmarked,isLiked", null))
    }

    // 좋아요 필터는 최근 10,000개 안에서 거른다(docs/plans/2026-11-17-bookmark-limit-liked-filter.md) - 딱 10,000개면 전부 본다
    @Test
    fun `좋아요가 10,000개면 isLiked 필터가 가장 오래된 좋아요까지 돌려주고 잘림 표시가 없다`() {
        val liked = seedHeavyLiker(bulkLikes = 9_998)

        val page = postService.getPostsByCursor(null, null, "isLiked", null, null, 20, liked.member)

        assertEquals(10_000, count("SELECT count(*) FROM post_reactions WHERE user_id = ?", liked.member))
        assertEquals(listOf(liked.oldest, busyPost), page.content.map { it.id }.take(2))
        assertEquals(null, page.likedFilterLimit)
    }

    // 하나 더 많으면 가장 오래전에 좋아요한 글이 빠지고, 화면이 안내할 수 있게 응답에 개수를 싣는다
    @Test
    fun `좋아요가 10,001개면 isLiked 필터에서 가장 오래된 좋아요가 빠지고 잘림 표시가 실린다`() {
        val liked = seedHeavyLiker(bulkLikes = 9_999)

        val page = postService.getPostsByCursor(null, null, "isLiked", null, null, 20, liked.member)

        assertEquals(10_001, count("SELECT count(*) FROM post_reactions WHERE user_id = ?", liked.member))
        assertEquals(busyPost, page.content.first().id)
        assertEquals(false, liked.oldest in page.content.map { it.id })
        assertEquals(10_000, page.likedFilterLimit)
    }

    // 북마크 필터와 함께 걸면 최근 좋아요 10,000개와 북마크의 교집합이다. 좋아요 필터를 안 건 목록에는 표시가 없다
    @Test
    fun `좋아요가 10,001개인 회원이 isBookmarked 와 함께 걸면 최근 좋아요 안의 북마크만 남는다`() {
        val liked = seedHeavyLiker(bulkLikes = 9_999)
        listOf(liked.oldest, busyPost).forEach { jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", liked.member, it) }

        val both = postService.getPostsByCursor(null, null, "isBookmarked,isLiked", null, null, 20, liked.member)
        val bookmarkedOnly = postService.getPostsByCursor(null, null, "isBookmarked", null, null, 20, liked.member)
        val unfiltered = postService.getPostsByCursor(null, null, null, null, null, 20, liked.member)

        assertEquals(listOf(busyPost), both.content.map { it.id })
        assertEquals(10_000, both.likedFilterLimit)
        assertEquals(listOf(liked.oldest, busyPost), bookmarkedOnly.content.map { it.id })
        assertEquals(null, bookmarkedOnly.likedFilterLimit)
        assertEquals(null, unfiltered.likedFilterLimit)
    }

    // 검색은 관련도 정렬 경로(점수 계산 뒤 정렬)를 탄다 - 그 경로에서도 id 조건이 함께 걸려야 한다
    @Test
    fun `검색어와 isBookmarked 를 함께 걸면 북마크한 글 가운데 검색에 걸린 글만 나온다`() {
        val bookmarkedHit = insertPost("kotlin coroutine guide")
        val notBookmarkedHit = insertPost("kotlin flow basics")
        jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", viewer, bookmarkedHit)

        val found = postService.getPostsByCursor(null, "kotlin", "isBookmarked", null, null, 20, viewer).content.map { it.id }
        val withoutFilter = postService.getPostsByCursor(null, "kotlin", null, null, null, 20, viewer).content.map { it.id }.toSet()

        assertEquals(listOf(bookmarkedHit), found)
        assertEquals(setOf(bookmarkedHit, notBookmarkedHit), withoutFilter)
    }

    // 북마크한 글이 없으면 검색 첫 쪽도 비고, 자판 보정 재검색으로 남의 글이 새지 않는다
    @Test
    fun `북마크한 글이 없으면 검색어를 함께 걸어도 빈 결과다`() {
        insertPost("kotlin flow basics")

        val page = postService.getPostsByCursor(null, "kotlin", "isBookmarked", null, null, 20, writer)

        assertEquals(emptyList<UUID>(), page.content.map { it.id })
        assertEquals(null, page.nextCursor)
    }

    // 필터를 건 채로 쪽을 넘겨도 순서(최신순)와 개수가 맞아야 한다
    @Test
    fun `isBookmarked 필터를 건 목록을 쪽을 넘겨 가며 끝까지 중복·누락 없이 읽는다`() {
        val extra = (1..5).map { insertPost("extra-$it", minutesAgo = it) }
        (extra + quietPost).forEach { jdbc.update("INSERT INTO bookmarks (user_id, post_id) VALUES (?, ?)", viewer, it) }
        insertPost("not-bookmarked", minutesAgo = 0)

        val pages = mutableListOf<List<UUID>>()
        var cursor: String? = null
        do {
            val page = postService.getPostsByCursor(null, null, "isBookmarked", null, cursor, 3, viewer)
            pages += page.content.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)

        assertEquals(extra + listOf(busyPost, quietPost), pages.flatten())
        assertEquals(listOf(3, 3, 1), pages.map { it.size })
    }

    // 북마크 insert는 네이티브 쿼리라 글 행이 DB에 먼저 나가 있어야 한다(FK). PostService가 flush를 부르는지는 여기서 가려지지
    // 않는다 - flush를 빼도 통과한다(원인은 추적하지 않았다. Hibernate가 네이티브 쿼리 앞에서 밀린 쓰기를 내보내는 것으로
    // 추정한다). 호출 순서는 PostServiceTest가 본다
    @Test
    fun `폴더를 지정해 글을 등록하면 북마크와 폴더 소속이 함께 저장된다`() {
        stubMetadata()

        val created = postService.createPost(viewer, PostCreateRequest(url = "https://example.com/new", folderIds = listOf(viewerFolderA, viewerFolderB, viewerFolderA)))

        assertEquals(true, created.userInteractions.isBookmarked)
        assertEquals(setOf(viewerFolderA, viewerFolderB), created.userInteractions.bookmarkFolderIds.toSet())
        assertEquals(1, created.stats.bookmarkCount)
        assertEquals(1, count("SELECT count(*) FROM bookmarks WHERE user_id = ? AND post_id = ?", viewer, created.id))
        assertEquals(2, count("SELECT count(*) FROM bookmark_folder_items WHERE user_id = ? AND post_id = ?", viewer, created.id))
    }

    @Test
    fun `북마크만 켜고 등록하면 폴더 없이 북마크만 저장된다`() {
        stubMetadata()

        val created = postService.createPost(viewer, PostCreateRequest(url = "https://example.com/new", bookmark = true))

        assertEquals(PostUserInteractions(isLiked = false, isBookmarked = true, bookmarkFolderIds = emptyList()), created.userInteractions)
        assertEquals(0, count("SELECT count(*) FROM bookmark_folder_items WHERE user_id = ? AND post_id = ?", viewer, created.id))
    }

    @Test
    fun `남의 폴더나 없는 폴더를 지정하면 북마크를 저장하지 않고 실패한다`() {
        stubMetadata()

        assertThrows(ForbiddenException::class.java) {
            postService.createPost(viewer, PostCreateRequest(url = "https://example.com/foreign", folderIds = listOf(viewerFolderA, othersFolder)))
        }
        assertThrows(BookmarkFolderNotFoundException::class.java) {
            postService.createPost(viewer, PostCreateRequest(url = "https://example.com/missing", folderIds = listOf(UUID.randomUUID())))
        }

        assertEquals(0, count("SELECT count(*) FROM bookmarks b JOIN posts p ON p.id = b.post_id WHERE b.user_id = ? AND p.url <> ?", viewer, "https://example.com/busy"))
    }

    // 폴더가 있는지를 먼저 보고 그다음 주인을 본다 - 순서가 바뀌면 같은 요청의 응답이 404에서 403으로 달라진다
    @Test
    fun `없는 폴더와 남의 폴더를 함께 지정하면 없는 폴더로 실패한다`() {
        stubMetadata()

        assertThrows(BookmarkFolderNotFoundException::class.java) {
            postService.createPost(viewer, PostCreateRequest(url = "https://example.com/both", folderIds = listOf(othersFolder, UUID.randomUUID())))
        }
    }

    // 북마크 저장 한도(docs/plans/2026-11-17-bookmark-limit-liked-filter.md) - 폴더가 없을 때처럼 등록 자체가 실패한다
    @Test
    fun `북마크가 한도만큼 있는 회원이 북마크와 함께 등록하면 실패하고 북마크 없이 등록하면 된다`() {
        stubMetadata()
        val full = UUID.randomUUID()
        jdbc.update("INSERT INTO members (id, nickname, email_verified) VALUES (?, 'full', true)", full)
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private) " +
                "SELECT gen_random_uuid(), ?, 'https://example.com/seed/' || g, 'seed ' || g, false FROM generate_series(1, 10000) g",
            writer,
        )
        jdbc.update("INSERT INTO bookmarks (user_id, post_id) SELECT ?, id FROM posts WHERE url LIKE 'https://example.com/seed/%'", full)

        assertThrows(BookmarkLimitExceededException::class.java) {
            postService.createPost(full, PostCreateRequest(url = "https://example.com/over", bookmark = true))
        }
        assertEquals(10_000, count("SELECT count(*) FROM bookmarks WHERE user_id = ?", full))

        val created = postService.createPost(full, PostCreateRequest(url = "https://example.com/plain"))

        assertEquals(false, created.userInteractions.isBookmarked)
    }

    private fun feedIds(filter: String, viewerId: UUID?): Set<UUID> = postService.getPostsByCursor(null, null, filter, null, null, 20, viewerId).content.map { it.id }.toSet()

    private data class HeavyLiker(val member: UUID, val oldest: UUID)

    /**
     * 좋아요가 bulkLikes + 2개인 회원을 만든다. 가장 오래전에 좋아요한 글(oldest)은 피드에서는 가장 새 글이라, 필터에 걸리면
     * 첫 쪽 맨 위에 온다. 가장 최근에 좋아요한 글은 busyPost이고 나머지는 하루 전 글들이다.
     */
    private fun seedHeavyLiker(bulkLikes: Int): HeavyLiker {
        val member = UUID.randomUUID()
        val oldest = insertPost("oldest-liked", minutesAgo = 0)
        jdbc.update("INSERT INTO members (id, nickname, email_verified) VALUES (?, 'heavy', true)", member)
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private, created_at) " +
                "SELECT gen_random_uuid(), ?, 'https://example.com/liked/' || g, 'liked ' || g, false, now() - interval '1 day' FROM generate_series(1, ?) g",
            writer,
            bulkLikes,
        )
        jdbc.update(
            "INSERT INTO post_reactions (user_id, post_id, created_at) SELECT ?, id, now() - interval '1 hour' FROM posts WHERE url LIKE 'https://example.com/liked/%'",
            member,
        )
        jdbc.update("INSERT INTO post_reactions (user_id, post_id, created_at) VALUES (?, ?, now() - interval '30 days')", member, oldest)
        jdbc.update("INSERT INTO post_reactions (user_id, post_id, created_at) VALUES (?, ?, now())", member, busyPost)

        return HeavyLiker(member, oldest)
    }

    private fun insertPost(title: String, minutesAgo: Int = 30): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private, created_at) VALUES (?, ?, ?, ?, false, now() - make_interval(mins => ?))",
            id,
            writer,
            "https://example.com/$title",
            title,
            minutesAgo,
        )

        return id
    }

    private fun stubMetadata() {
        `when`(urlMetadataExtractor.extract(anyString()))
            .thenReturn(UrlMetadata(title = "제목", description = null, ogImage = null, tags = emptyList(), pageContent = null))
    }

    private fun count(sql: String, vararg args: Any?): Int = jdbc.queryForObject(sql, Int::class.java, *args)!!
}
