package com.example.linksphere.domain.post

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.comment.TableComment
import com.example.linksphere.domain.interaction.BookmarkListSort
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.global.common.CursorCodec
import com.example.linksphere.global.common.CursorKeyType
import com.example.linksphere.global.common.KeysetSlice
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

// 세 목록의 cursor 조회를 실제 Postgres에서 검증한다 - 끝까지 읽은 결과가 전체 정렬과 같은가, 쪽 사이 쓰기에 어떻게 반응하는가,
// 설계가 기대는 SQL 형태(인덱스 범위 탐색, 점수를 안쪽에서 한 번만 계산)가 실제로 만들어지는가. 설계 근거는 docs/PAGINATION.md.
@PostgresIntegrationTest
@ExtendWith(OutputCaptureExtension::class)
class CursorPaginationIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var postRepository: PostRepository

    @Autowired private lateinit var bookmarkRepository: BookmarkRepository

    @Autowired private lateinit var commentRepository: CommentRepository

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", me)
        jdbc.update("INSERT INTO members (id) VALUES (?)", other)
    }

    // ── 피드 ──────────────────────────────────────────────

    @Test
    fun `피드 최신순은 같은 시각의 글까지 전체 정렬과 똑같이 끝까지 읽는다`() {
        repeat(5) { insertPost("같은 시각 $it") }
        insertPost("1마이크로초 뒤", "2026-01-01 00:00:00.123457+09")
        insertPost("1마이크로초 앞", "2026-01-01 00:00:00.123455+09")
        insertPost("남의 비공개 글", isPrivate = true)

        val expected = ids("SELECT id FROM posts WHERE is_private = false ORDER BY created_at DESC, id DESC")

        assertEquals(7, expected.size)
        assertEquals(expected, walkFeed(PostListSort.LATEST))
    }

    @Test
    fun `개수가 쪽 크기에 딱 맞으면 빈 쪽을 한 번 더 요구하지 않는다`() {
        repeat(6) { insertPost("글 $it") }

        val first = findFeed(PostListSort.LATEST, after = null, size = 3)
        val second = findFeed(PostListSort.LATEST, after = first.nextKeys, size = 3)

        assertNotNull(first.nextKeys)
        assertEquals(3, second.content.size)
        assertNull(second.nextKeys)
    }

    // 등록과 삭제를 서로 다른 쪽 사이에 한다. 같은 쪽 사이에 하면 offset 구현에서도 밀림이 상쇄돼 이 테스트가 통과한다.
    @Test
    fun `쪽 사이에 새 글이 올라오거나 방금 본 글이 지워져도 중복·누락 없이 이어 읽는다`() {
        val seeded = (1..9).map { insertPost("글 $it", "2026-01-0$it 00:00:00+09") }.reversed()

        val walked =
            walkFeed(PostListSort.LATEST) { page ->
                when (page) {
                    1 -> insertPost("새 글", "2026-02-01 00:00:00+09")
                    2 -> jdbc.update("DELETE FROM posts WHERE id = ?", seeded[5])
                }
            }

        assertEquals(seeded, walked)
    }

    @Test
    fun `쪽 사이에 비공개로 바뀐 글은 다음 쪽에서 빠진다`() {
        val seeded = (1..6).map { insertPost("글 $it", "2026-01-0$it 00:00:00+09") }.reversed()

        val walked =
            walkFeed(PostListSort.LATEST) { page ->
                if (page == 1) {
                    jdbc.update("UPDATE posts SET is_private = true WHERE id = ?", seeded[4])
                }
            }

        assertEquals(seeded - seeded[4], walked)
    }

    // 점수 8(제목 3 + 토큰 5)짜리 동점을 만들어 쪽 경계가 동점 한가운데 오게 한다
    @Test
    fun `피드 검색은 점수 동점까지 한 번에 받은 결과와 똑같이 끝까지 읽는다`() {
        val exact = insertPost("react", "2026-01-01 00:00:00+09")
        repeat(7) { insertPost("코드 react$it", if (it < 4) "2026-01-02 00:00:00.5+09" else "2026-01-03 00:00:00+09") }
        insertPost("vue")

        val walked = walkFeed(PostListSort.RELEVANCE, search = "react")
        val atOnce = findFeed(PostListSort.RELEVANCE, search = "react", after = null, size = 50)

        assertEquals(atOnce.content.map { it.id }, walked)
        assertEquals(8, walked.toSet().size)
        assertEquals(exact, walked.first())
    }

    @Test
    fun `피드 검색은 쪽 사이에 앞쪽으로 새 글이 끼거나 방금 본 글이 지워져도 중복·누락 없이 이어 읽는다`() {
        val seeded = (1..9).map { insertPost("react 제목 $it", "2026-01-0$it 00:00:00+09") }.reversed()

        val walked =
            walkFeed(PostListSort.RELEVANCE, search = "react") { page ->
                when (page) {
                    1 -> insertPost("react", "2026-02-01 00:00:00+09")
                    2 -> jdbc.update("DELETE FROM posts WHERE id = ?", seeded[5])
                }
            }

        assertEquals(seeded, walked)
    }

    // 현재 동작 기록(docs/PAGINATION.md §5.3): 점수가 바뀐 글은 cursor도 놓친다. 제목을 고쳐 점수가 오른 경우다.
    @Test
    fun `피드 검색은 아직 안 본 글의 점수가 cursor 위로 바뀌면 그 글을 놓친다`() {
        val seeded = (1..6).map { insertPost("react 제목 $it", "2026-01-0$it 00:00:00+09") }.reversed()

        val walked =
            walkFeed(PostListSort.RELEVANCE, search = "react") { page ->
                if (page == 1) {
                    jdbc.update("UPDATE posts SET title = 'react' WHERE id = ?", seeded[5])
                }
            }

        assertEquals(seeded.dropLast(1), walked)
    }

    @Test
    fun `의미 점수의 소수 동점도 cursor 왕복 뒤 끝까지 읽는다`() {
        repeat(3) { insertPost("전혀 다른 글 $it", embedding = vectorAt(0.2)) }
        repeat(3) { insertPost("또 다른 글 $it", embedding = vectorAt(0.1)) }
        val keyword = insertPost("react 제목")
        val far = insertPost("무관한 글", embedding = vectorAt(1.5))
        val query = vectorAt(0.0).toFloatArray()

        val walked = walkFeed(PostListSort.RELEVANCE, search = "react", size = 2, embedding = query)
        val atOnce = findFeed(PostListSort.RELEVANCE, search = "react", after = null, size = 50, embedding = query)

        assertEquals(atOnce.content.map { it.id }, walked)
        assertEquals(7, walked.toSet().size)
        assertTrue(keyword in walked)
        assertFalse(far in walked)
    }

    @Test
    fun `건수는 요청했을 때만 세고 전체 후보를 센다`() {
        repeat(5) { insertPost("react $it") }
        insertPost("vue")

        assertEquals(5L, findFeed(PostListSort.RELEVANCE, search = "react", after = null, size = 2, withTotal = true).total)
        assertNull(findFeed(PostListSort.RELEVANCE, search = "react", after = null, size = 2).total)
    }

    // ── 북마크 ────────────────────────────────────────────

    @Test
    fun `북마크 정렬 4종은 동점과 열람 없는 글까지 전체 정렬과 똑같이 끝까지 읽는다`() {
        seedBookmarks()

        mapOf(
            BookmarkListSort.LATEST to "b.created_at DESC, b.post_id DESC",
            BookmarkListSort.OLDEST to "b.created_at ASC, b.post_id ASC",
            BookmarkListSort.TITLE to "left(p.title, 200) ASC, b.created_at DESC, b.post_id DESC",
            BookmarkListSort.VIEWED to "coalesce(v.viewed_at, TIMESTAMP '1970-01-01') DESC, b.created_at DESC, b.post_id DESC",
        ).forEach { (sort, order) ->
            val expected =
                ids(
                    "SELECT b.post_id FROM bookmarks b JOIN posts p ON p.id = b.post_id " +
                        "LEFT JOIN post_views v ON v.post_id = b.post_id AND v.user_id = b.user_id " +
                        "WHERE b.user_id = ? AND (p.is_private = false OR p.user_id = ?) ORDER BY $order",
                    me,
                    me,
                )

            assertEquals(10, expected.size, sort.name)
            assertEquals(expected, walkBookmarks(sort), sort.name)
        }
    }

    @Test
    fun `폴더 조건은 계산식 키의 파생 테이블 안에서도 그대로 걸린다`() {
        val folder = UUID.randomUUID()
        jdbc.update("INSERT INTO bookmark_folders (id, user_id, name) VALUES (?, ?, 'A')", folder, me)
        val inFolder = (1..5).map { insertPost("폴더 글 $it") }
        val outside = (1..4).map { insertPost("미분류 글 $it") }
        (inFolder + outside).forEach { bookmark(it) }
        inFolder.forEach { jdbc.update("INSERT INTO bookmark_folder_items (user_id, post_id, folder_id) VALUES (?, ?, ?)", me, it, folder) }

        BookmarkListSort.entries.filter { it != BookmarkListSort.RELEVANCE }.forEach { sort ->
            assertEquals(inFolder.toSet(), walkBookmarks(sort, folderId = folder, size = 2).toSet(), sort.name)
            assertEquals(outside.toSet(), walkBookmarks(sort, onlyUncategorized = true, size = 2).toSet(), sort.name)
        }
    }

    @Test
    fun `북마크 검색은 점수 동점까지 한 번에 받은 결과와 똑같이 끝까지 읽는다`() {
        repeat(7) { bookmark(insertPost("코드 react$it"), "2026-03-0${it % 2 + 1} 00:00:00.654321+09") }
        bookmark(insertPost("vue"))
        insertPost("북마크 안 한 react 글")

        val walked = walkBookmarks(BookmarkListSort.RELEVANCE, search = "react")
        val atOnce = bookmarkRepository.findBookmarkedPostsAfter(me, null, false, BookmarkListSort.RELEVANCE, "react", null, 50)

        assertEquals(atOnce.content.map { it.id }, walked)
        assertEquals(7, walked.toSet().size)
    }

    // 끼어드는 글은 어느 정렬에서든 cursor 앞에 오도록 제목·시각을 잡는다. 지우는 글은 둘째 쪽의 마지막 글, 즉 cursor의 기준 행이다.
    @ParameterizedTest
    @EnumSource(BookmarkListSort::class)
    fun `북마크는 쪽 사이에 앞쪽으로 새 글이 끼거나 방금 본 글이 빠져도 중복·누락 없이 이어 읽는다`(sort: BookmarkListSort) {
        val search = "react".takeIf { sort == BookmarkListSort.RELEVANCE }
        (1..9).forEach {
            val post = insertPost("react 제목 $it")
            bookmark(post, "2026-03-0$it 00:00:00+09")
            view(post, "2026-04-0$it 00:00:00")
        }
        val expected = walkBookmarks(sort, search)

        val walked =
            walkBookmarks(sort, search) { page ->
                when (page) {
                    1 -> {
                        val added = insertPost("react")
                        bookmark(added, if (sort == BookmarkListSort.OLDEST) "2026-02-01 00:00:00+09" else "2026-05-01 00:00:00+09")
                        view(added, "2026-05-01 00:00:00")
                    }
                    2 -> jdbc.update("DELETE FROM bookmarks WHERE user_id = ? AND post_id = ?", me, expected[5])
                }
            }

        assertEquals(9, expected.size)
        assertEquals(expected, walked)
    }

    // 현재 동작 기록(docs/PAGINATION.md §5.3): 제목이 cursor를 넘어 바뀌면 앞으로 간 글은 놓치고 뒤로 간 글은 다시 나온다. FE가 id로 중복을 거른다.
    @Test
    fun `북마크 제목순은 제목이 cursor를 넘어 바뀌면 안 본 글을 놓치고 본 글을 다시 돌려준다`() {
        val posts = (1..6).map { insertPost("b $it").also { post -> bookmark(post) } }

        val walked =
            walkBookmarks(BookmarkListSort.TITLE) { page ->
                if (page == 1) {
                    jdbc.update("UPDATE posts SET title = 'a' WHERE id = ?", posts[5])
                    jdbc.update("UPDATE posts SET title = 'c' WHERE id = ?", posts[0])
                }
            }

        assertEquals(posts.take(5) + posts[0], walked)
    }

    @Test
    fun `북마크 검색은 아직 안 본 글의 점수가 cursor 위로 바뀌면 그 글을 놓친다`() {
        val posts = (1..6).map { insertPost("react 제목 $it").also { post -> bookmark(post, "2026-03-0$it 00:00:00+09") } }

        val walked =
            walkBookmarks(BookmarkListSort.RELEVANCE, search = "react") { page ->
                if (page == 1) {
                    jdbc.update("UPDATE posts SET title = 'react' WHERE id = ?", posts[0])
                }
            }

        assertEquals(posts.drop(1).reversed(), walked)
    }

    // 제목은 길이 제한이 없는데 cursor는 쿼리 문자열로 오가고, 운영 WAF가 쿼리 문자열 2,048바이트를 넘으면 막는다.
    // 앞 200자가 같은 글끼리는 북마크 시각으로 갈린다.
    @Test
    fun `제목이 5,000자여도 cursor는 1,200바이트 미만이고 앞 200자가 같은 글까지 끝까지 읽는다`() {
        val title = "가".repeat(5_000)
        val posts =
            (1..3).map {
                val post = insertPost(title + it)
                bookmark(post, "2026-03-0$it 00:00:00.123456+09")
                post
            }

        val first = bookmarkRepository.findBookmarkedPostsAfter(me, null, false, BookmarkListSort.TITLE, null, null, 1)
        val cursor = CursorCodec.encode(BookmarkListSort.TITLE.name, first.nextKeys!!)

        assertTrue(cursor.toByteArray().size < 1_200, "cursor ${cursor.toByteArray().size}바이트")
        assertEquals(posts.reversed(), walkBookmarks(BookmarkListSort.TITLE, size = 1))
    }

    // 현재 동작 기록(docs/PAGINATION.md §5.3): 정렬 값이 바뀐 글은 cursor도 놓친다. 내 동작이면 FE가 목록을 다시 받아 복구된다.
    @Test
    fun `아직 안 본 글의 정렬 값이 cursor 위로 바뀌면 그 글을 놓친다`() {
        val posts =
            (1..6).map {
                val post = insertPost("글 $it")
                bookmark(post)
                view(post, "2026-03-0$it 00:00:00")
                post
            }

        val walked =
            walkBookmarks(BookmarkListSort.VIEWED) { page ->
                if (page == 1) {
                    jdbc.update("UPDATE post_views SET viewed_at = TIMESTAMP '2026-04-01' WHERE post_id = ?", posts[0])
                }
            }

        assertEquals(posts.drop(1).reversed(), walked)
    }

    // ── 내 댓글 ───────────────────────────────────────────

    @Test
    fun `내 댓글은 timestamp 마이크로초와 같은 시각 동점을 왕복해 끝까지 읽는다`() {
        val post = insertPost("글")
        val privatePost = insertPost("남의 비공개 글", isPrivate = true)
        val tied = LocalDateTime.of(2026, 3, 1, 0, 0, 0, 123_456_000)
        repeat(5) { insertComment(post, tied) }
        insertComment(post, tied.plusNanos(1_000))
        insertComment(post, tied.minusNanos(1_000))
        insertComment(post, tied, isDeleted = true)
        insertComment(privatePost, tied)
        insertComment(post, tied, author = other)

        val expected =
            ids(
                "SELECT c.id FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.user_id = ? AND c.is_deleted = false " +
                    "AND (p.is_private = false OR p.user_id = ?) ORDER BY c.created_at DESC, c.id DESC",
                me,
                me,
            )

        assertEquals(7, expected.size)
        assertEquals(expected, walkComments())
    }

    @Test
    fun `내 댓글은 쪽 사이에 새 댓글이 달리거나 방금 본 댓글이 지워져도 중복·누락 없이 이어 읽는다`() {
        val post = insertPost("글")
        val seeded = (1..9).map { insertComment(post, LocalDateTime.of(2026, 3, it, 0, 0)) }.reversed()

        val walked =
            walkComments { page ->
                when (page) {
                    1 -> insertComment(post, LocalDateTime.of(2026, 4, 1, 0, 0))
                    2 -> jdbc.update("UPDATE comments SET is_deleted = true WHERE id = ?", seeded[5])
                }
            }

        assertEquals(seeded, walked)
    }

    // ── SQL 형태 ──────────────────────────────────────────

    @Test
    fun `피드 최신순은 정렬 키 인덱스로 시작 지점을 찾는다`(output: CapturedOutput) {
        val keys = listOf<Comparable<*>>(LocalDateTime.of(2026, 3, 1, 0, 0), UUID.randomUUID())

        findFeed(PostListSort.LATEST, after = keys, size = 10)

        val time = Timestamp.valueOf(keys[0] as LocalDateTime)
        assertUsesIndex(lastSelect(output, "from posts"), "idx_posts_created_at_id", false, time, time, time, keys[1], 11)
    }

    @Test
    fun `북마크 최신순·오래된순은 같은 인덱스로 시작 지점을 찾는다`(output: CapturedOutput) {
        val keys = listOf<Comparable<*>>(LocalDateTime.of(2026, 3, 1, 0, 0), UUID.randomUUID())
        val time = Timestamp.valueOf(keys[0] as LocalDateTime)

        listOf(BookmarkListSort.LATEST, BookmarkListSort.OLDEST).forEach { sort ->
            bookmarkRepository.findBookmarkedPostsAfter(me, null, false, sort, null, keys, 10)

            assertUsesIndex(lastSelect(output, "from bookmarks"), "idx_bookmarks_user_created_at_post", me, false, me, time, time, time, keys[1], 11)
        }
    }

    @Test
    fun `내 댓글은 회원별 정렬 키 인덱스로 시작 지점을 찾는다`(output: CapturedOutput) {
        val createdAt = LocalDateTime.of(2026, 3, 1, 0, 0)
        val id = UUID.randomUUID()

        commentRepository.findMyCommentsAfter(me, createdAt, id, Limit.of(11))

        val time = Timestamp.valueOf(createdAt)
        assertUsesIndex(lastSelect(output, "from comments"), "idx_comments_user_created_at_id", me, me, time, time, time, id, 11)
    }

    // 점수 식이 바깥 WHERE·ORDER BY에 다시 나오면 행마다 다시 계산한다. offset 0이 빠지면 Postgres가 파생 테이블을 합쳐 같은 일이 생긴다.
    @Test
    fun `검색 점수는 파생 테이블 안에서 한 번만 계산하고 바깥에서는 값만 비교한다`(output: CapturedOutput) {
        insertPost("react")

        findFeed(PostListSort.RELEVANCE, search = "react", after = listOf(10.0, LocalDateTime.of(2026, 3, 1, 0, 0), UUID.randomUUID()), size = 3)

        val sql = lastSelect(output, "derived1_0")
        val outer = sql.substringAfterLast(" offset 0 rows)")

        assertTrue(sql.contains(" offset 0 rows)"), sql)
        assertFalse(outer.contains("case when") || outer.contains("regexp_replace"), outer)
        // 점수를 double로 변환해야 소수 의미 점수와 키워드 전용 정수 점수가 같은 cursor 형식으로 왕복한다
        assertTrue(sql.contains(" as float(53))"), sql)
    }

    // 최근 열람순 키는 북마크 행만으로 계산돼 조인 전에 거를 수 있고, 파생 테이블이면 offset 0이 그걸 막는다. 제목은 글 표의 컬럼이라 반대다(docs/PAGINATION.md §5.2).
    @Test
    fun `최근 열람순은 조건을 WHERE에 바로 쓰고 제목순은 파생 테이블에서 비교한다`(output: CapturedOutput) {
        val time = LocalDateTime.of(2026, 3, 1, 0, 0)

        bookmarkRepository.findBookmarkedPostsAfter(me, null, false, BookmarkListSort.VIEWED, null, listOf(time, time, UUID.randomUUID()), 10)
        assertFalse(lastSelect(output, "from bookmarks").contains("derived1_0"))

        bookmarkRepository.findBookmarkedPostsAfter(me, null, false, BookmarkListSort.TITLE, null, listOf("제목", time, UUID.randomUUID()), 10)
        assertTrue(lastSelect(output, "from bookmarks").contains("derived1_0"))
    }

    // ── 도우미 ────────────────────────────────────────────

    private fun findFeed(sort: PostListSort, search: String? = null, after: List<Comparable<*>>?, size: Int, embedding: FloatArray? = null, withTotal: Boolean = false) = postRepository.findPostsAfter(null, search, null, null, null, sort, after, size, embedding, withTotal)

    private fun walkFeed(sort: PostListSort, search: String? = null, size: Int = 3, embedding: FloatArray? = null, between: (Int) -> Unit = {}): List<UUID> = walk(sort.name, sort.keyTypes, between) { after -> findFeed(sort, search, after, size, embedding) }.map { it.id!! }

    private fun walkBookmarks(
        sort: BookmarkListSort,
        search: String? = null,
        folderId: UUID? = null,
        onlyUncategorized: Boolean = false,
        size: Int = 3,
        between: (Int) -> Unit = {},
    ): List<UUID> = walk(sort.name, sort.keyTypes, between) { after ->
        bookmarkRepository.findBookmarkedPostsAfter(me, folderId, onlyUncategorized, sort, search, after, size)
    }.map { it.id!! }

    private fun walkComments(size: Int = 3, between: (Int) -> Unit = {}): List<UUID> = walk("LATEST", listOf(CursorKeyType.TIME, CursorKeyType.ID), between) { after ->
        val rows: List<TableComment> =
            if (after == null) {
                commentRepository.findMyCommentsFirst(me, Limit.of(size + 1))
            } else {
                commentRepository.findMyCommentsAfter(me, after[0] as LocalDateTime, after[1] as UUID, Limit.of(size + 1))
            }
        val page = rows.take(size)
        KeysetSlice(page, page.lastOrNull().takeIf { rows.size > size }?.let { listOf(it.createdAt, it.id) })
    }.map { it.id }

    // 서비스처럼 키를 cursor 문자열로 왕복시켜 끝까지 읽는다. between은 쪽 번호를 받아 쪽 사이에 쓰기를 끼워 넣는다.
    private fun <T> walk(sort: String, keyTypes: List<CursorKeyType>, between: (Int) -> Unit, fetch: (List<Comparable<*>>?) -> KeysetSlice<T>): List<T> {
        val rows = mutableListOf<T>()
        var cursor: String? = null
        var page = 0

        do {
            val slice = fetch(cursor?.let { CursorCodec.decode(it, sort, keyTypes).keys })
            rows += slice.content
            cursor = slice.nextKeys?.let { CursorCodec.encode(sort, it) }
            page++
            between(page)
        } while (cursor != null)

        return rows
    }

    // 동점이 쪽 경계에 걸리도록 같은 시각·같은 제목을 섞고, 열람 기록이 없는 글과 남의 비공개 글을 넣는다
    private fun seedBookmarks() {
        val tied = "2026-03-01 00:00:00.654321+09"
        repeat(4) { bookmark(insertPost("같은 제목"), tied) }
        repeat(3) {
            val post = insertPost("제목 $it")
            bookmark(post, "2026-03-0${it + 2} 00:00:00.000001+09")
            view(post, "2026-03-10 00:00:00.123456")
        }
        repeat(3) {
            val post = insertPost("다른 제목 $it")
            bookmark(post, tied)
            view(post, "2026-03-1$it 00:00:00")
        }
        bookmark(insertPost("남의 비공개 글", isPrivate = true), tied)
    }

    private fun lastSelect(output: CapturedOutput, fragment: String): String = output.out.lines()
        .last { it.startsWith("Hibernate: select") && it.contains(fragment) }
        .removePrefix("Hibernate: ")

    // 표가 작으면 Postgres가 인덱스가 있어도 순차·비트맵 탐색 뒤 정렬을 고른다 - 이 트랜잭션에서만 꺼 인덱스 순서로 읽을 수 있는 형태인지 본다
    private fun assertUsesIndex(sql: String, index: String, vararg args: Any?) {
        jdbc.execute("SET LOCAL enable_seqscan = off")
        jdbc.execute("SET LOCAL enable_bitmapscan = off")
        val plan = jdbc.queryForList("EXPLAIN $sql", String::class.java, *args).joinToString("\n")

        assertTrue(plan.contains(index) && plan.contains("Index Cond") && plan.contains("created_at"), plan)
        assertFalse(plan.contains("Sort"), plan)
    }

    private fun ids(sql: String, vararg args: Any): List<UUID> = jdbc.queryForList(sql, UUID::class.java, *args)

    private fun insertPost(title: String, createdAt: String = "2026-01-01 00:00:00.123456+09", isPrivate: Boolean = false, embedding: List<Float>? = null): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private, created_at, embedding) " +
                "VALUES (?, ?, 'https://example.com', ?, ?, CAST(? AS timestamptz), CAST(? AS extensions.vector))",
            id,
            other,
            title,
            isPrivate,
            createdAt,
            embedding?.joinToString(",", "[", "]"),
        )
        return id
    }

    private fun bookmark(post: UUID, createdAt: String = "2026-03-01 00:00:00.654321+09") {
        jdbc.update("INSERT INTO bookmarks (user_id, post_id, created_at) VALUES (?, ?, CAST(? AS timestamptz))", me, post, createdAt)
    }

    private fun view(post: UUID, viewedAt: String) {
        jdbc.update("INSERT INTO post_views (user_id, post_id, viewed_at) VALUES (?, ?, CAST(? AS timestamp))", me, post, viewedAt)
    }

    private fun insertComment(post: UUID, createdAt: LocalDateTime, author: UUID = me, isDeleted: Boolean = false): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO comments (id, post_id, user_id, content, is_deleted, created_at) VALUES (?, ?, ?, 'c', ?, ?)",
            id,
            post,
            author,
            isDeleted,
            createdAt,
        )
        return id
    }

    // 첫 두 축 평면에서 angle만큼 돌린 단위 벡터. 코사인 거리가 소수가 되어 의미 점수에 소수 동점을 만든다
    private fun vectorAt(angle: Double): List<Float> = List(768) {
        when (it) {
            0 -> cos(angle).toFloat()
            1 -> sin(angle).toFloat()
            else -> 0f
        }
    }
}
