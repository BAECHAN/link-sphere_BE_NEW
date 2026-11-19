package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.domain.post.PostLinkParser
import com.example.linksphere.domain.post.PostLookupService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDateTime
import java.util.UUID

/**
 * "내 댓글" 목록을 Service부터 실제 Postgres까지 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-8).
 *
 * 댓글은 글을 조인하지 않는다. 댓글을 읽은 뒤 post에 "내가 볼 수 있는 글의 제목"을 묻고, 답이 오지 않은 글의 댓글을 뺀 다음
 * 빠진 만큼 이어서 다시 읽는다. 그래서 "남의 비공개 글에 단 댓글이 빠진다"와 "빠져도 쪽이 차고 끝까지 중복·누락 없이 읽힌다"는
 * 두 패키지의 쿼리가 함께 돌아야 확인된다 - 예전에는 CommentRepository의 조인 쿼리 하나가 맡던 일이다.
 */
@PostgresIntegrationTest
@Import(CommentService::class, PostLookupService::class, PostLinkParser::class, MemberService::class)
class MyCommentIntegrationTest {

    @Autowired private lateinit var commentService: CommentService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private val myPublic = UUID.randomUUID()
    private val myPrivate = UUID.randomUUID()
    private val othersPublic = UUID.randomUUID()
    private val othersPrivate = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", me)
        jdbc.update("INSERT INTO members (id) VALUES (?)", other)
        insertPost(myPublic, owner = me, title = "내 공개 글")
        insertPost(myPrivate, owner = me, title = "내 비공개 글", isPrivate = true)
        insertPost(othersPublic, owner = other, title = "남의 공개 글")
        insertPost(othersPrivate, owner = other, title = "남의 비공개 글", isPrivate = true)
    }

    // 댓글을 단 뒤 원글이 비공개로 바뀌면 남의 글 제목이 새지 않아야 한다
    @Test
    fun `남의 비공개 글에 단 내 댓글은 빠지고 내 비공개 글에 단 댓글은 제목과 함께 나온다`() {
        val onOthersPublic = insertComment(othersPublic, at(10))
        val onMyPrivate = insertComment(myPrivate, at(11))
        val onMyPublic = insertComment(myPublic, at(12))
        insertComment(othersPrivate, at(13))
        insertComment(othersPublic, at(14), isDeleted = true)
        insertComment(myPublic, at(15), author = other)

        val page = commentService.getMyCommentsByCursor(me, null, 20)

        assertEquals(listOf(onMyPublic, onMyPrivate, onOthersPublic), page.content.map { it.id })
        assertEquals(listOf("내 공개 글", "내 비공개 글", "남의 공개 글"), page.content.map { it.postTitle })
        assertNull(page.nextCursor)
    }

    // 가려진 댓글이 쪽 경계 어디에 끼어 있어도(쪽의 처음·가운데·끝, 여러 개 연속) 보이는 댓글만 쪽 크기대로 순서대로 나와야 한다
    @Test
    fun `가려진 댓글이 섞여 있어도 쪽을 채워 가며 끝까지 중복·누락 없이 읽는다`() {
        val hours = (1..20).toList()
        // 3의 배수 시각과 17~19시는 남의 비공개 글에 단 댓글이다 - 19·18·17시는 연속으로 가려진다
        val hidden = hours.filter { it % 3 == 0 || it in 17..19 }.toSet()
        val visible =
            hours.mapNotNull { hour ->
                val id = insertComment(if (hour in hidden) othersPrivate else othersPublic, at(hour))
                id.takeIf { hour !in hidden }
            }.reversed()

        val pages = walk(size = 3)

        assertEquals(visible, pages.flatten())
        // 마지막 쪽을 뺀 모든 쪽이 꽉 찬다 - 가려진 댓글 때문에 쪽이 덜 차지 않는다
        assertEquals(List(pages.size - 1) { 3 }, pages.dropLast(1).map { it.size })
    }

    // 같은 시각의 댓글은 id 역순이다. 가려진 댓글이 동점 사이에 끼어도 cursor가 그 자리를 건너뛰지도 되읽지도 않아야 한다
    @Test
    fun `작성 시각이 같은 댓글 사이에 가려진 댓글이 끼어도 순서와 개수가 맞다`() {
        val tied = LocalDateTime.of(2026, 3, 1, 0, 0, 0, 123_456_000)
        repeat(4) { insertComment(othersPublic, tied) }
        repeat(3) { insertComment(othersPrivate, tied) }

        val expected =
            jdbc.queryForList(
                "SELECT c.id FROM comments c JOIN posts p ON p.id = c.post_id WHERE c.user_id = ? AND c.is_deleted = false " +
                    "AND (p.is_private = false OR p.user_id = ?) ORDER BY c.created_at DESC, c.id DESC",
                UUID::class.java,
                me,
                me,
            )

        assertEquals(4, expected.size)
        assertEquals(expected, walk(size = 2).flatten())
    }

    // 쪽이 꽉 찬 뒤에 남은 댓글이 모두 가려진 글의 것이면 다음 쪽이 없다 - cursor를 내면 FE가 빈 쪽을 받는다
    @Test
    fun `쪽이 꽉 찬 뒤 남은 댓글이 모두 가려져 있으면 다음 cursor를 내지 않는다`() {
        val newest = insertComment(othersPublic, at(10))
        val second = insertComment(othersPublic, at(9))
        (6..8).forEach { insertComment(othersPrivate, at(it)) }

        val page = commentService.getMyCommentsByCursor(me, null, 2)

        assertEquals(listOf(newest, second), page.content.map { it.id })
        assertNull(page.nextCursor)
    }

    // 첫 쪽을 받은 뒤 다음 쪽의 첫 댓글이 달린 글이 비공개로 바뀐 경우다 - cursor 바로 다음 행이 가려져도 그 뒤를 이어 읽는다
    @Test
    fun `cursor 바로 다음 댓글이 가려져 있어도 그 뒤 댓글부터 이어서 나온다`() {
        val turningPrivate = UUID.randomUUID()
        insertPost(turningPrivate, owner = other, title = "곧 비공개가 될 글")
        val newest = insertComment(othersPublic, at(10))
        val second = insertComment(othersPublic, at(9))
        insertComment(turningPrivate, at(8))
        val last = insertComment(othersPublic, at(7))

        val first = commentService.getMyCommentsByCursor(me, null, 2)
        jdbc.update("UPDATE posts SET is_private = true WHERE id = ?", turningPrivate)
        val next = commentService.getMyCommentsByCursor(me, first.nextCursor, 2)

        assertEquals(listOf(newest, second), first.content.map { it.id })
        assertEquals(listOf(last), next.content.map { it.id })
        assertNull(next.nextCursor)
    }

    // 쪽 크기가 1이어도 가려진 댓글을 여러 개 넘겨 보이는 댓글을 찾는다(다시 읽을 때는 한 번에 넉넉히 읽는다)
    @Test
    fun `쪽 크기가 1이고 가려진 댓글이 여러 개 이어져도 그 뒤의 보이는 댓글이 나온다`() {
        (11..18).forEach { insertComment(othersPrivate, at(it)) }
        val visible = insertComment(othersPublic, at(10))

        val page = commentService.getMyCommentsByCursor(me, null, 1)

        assertEquals(listOf(visible), page.content.map { it.id })
        assertNull(page.nextCursor)
    }

    private fun walk(size: Int): List<List<UUID>> {
        val pages = mutableListOf<List<UUID>>()
        var cursor: String? = null

        do {
            val page = commentService.getMyCommentsByCursor(me, cursor, size)
            pages += page.content.map { it.id }
            cursor = page.nextCursor
        } while (cursor != null)

        return pages
    }

    private fun at(hour: Int): LocalDateTime = LocalDateTime.of(2026, 10, 1, hour, 0)

    private fun insertPost(id: UUID, owner: UUID, title: String, isPrivate: Boolean = false) {
        jdbc.update("INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', ?, ?)", id, owner, title, isPrivate)
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
}
