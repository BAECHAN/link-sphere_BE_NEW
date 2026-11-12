package com.example.linksphere.domain.comment

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime
import java.util.UUID

// "내 댓글" 목록(findMyCommentsFirst/After)의 WHERE·정렬을 실제 Postgres에서 검증한다
@PostgresIntegrationTest
class CommentRepositoryIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var commentRepository: CommentRepository

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private val myPublic = UUID.randomUUID()
    private val myPrivate = UUID.randomUUID()
    private val othersPublic = UUID.randomUUID()
    private val othersPrivate = UUID.randomUUID()

    // 작성 시각이 같은 두 댓글은 id 역순이어야 한다. Postgres는 uuid를 바이트 단위로 비교해 Java UUID.compareTo와
    // 순서가 다를 수 있으므로, 기대 순서가 분명하도록 이 둘만 id를 고정한다
    private val mineOnMyPrivate = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val mineOnMyPublic = UUID.fromString("00000000-0000-0000-0000-000000000002")
    private val mineOnOthersPublic = UUID.randomUUID()
    private val mineOnOthersPrivate = UUID.randomUUID()
    private val myTombstone = UUID.randomUUID()
    private val othersComment = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        insertMember(me)
        insertMember(other)
        insertPost(myPublic, owner = me)
        insertPost(myPrivate, owner = me, isPrivate = true)
        insertPost(othersPublic, owner = other)
        insertPost(othersPrivate, owner = other, isPrivate = true)

        // 보여야 하는 댓글 3개
        insertComment(mineOnOthersPublic, othersPublic, author = me, createdAt = at(10))
        insertComment(mineOnMyPrivate, myPrivate, author = me, createdAt = at(11))
        insertComment(mineOnMyPublic, myPublic, author = me, createdAt = at(11))

        // 빠져야 하는 댓글 3개 - 가장 최신이라 조건이 깨지면 맨 앞에 끼어든다
        insertComment(mineOnOthersPrivate, othersPrivate, author = me, createdAt = at(12))
        insertComment(myTombstone, othersPublic, author = me, createdAt = at(12), isDeleted = true)
        insertComment(othersComment, myPublic, author = other, createdAt = at(12))
    }

    @Test
    fun `findMyCommentsFirst 는 내가 쓴 댓글만 돌려준다`() {
        val comments = commentRepository.findMyCommentsFirst(me, Limit.of(20))

        assertEquals(false, comments.any { it.id == othersComment })
        assertTrue(comments.all { it.userId == me })
    }

    // 댓글을 단 뒤 원글이 비공개로 바뀌면 남의 글 제목이 새지 않도록 막는 게이트
    @Test
    fun `남의 비공개 글에 단 내 댓글은 빠진다`() {
        val comments = commentRepository.findMyCommentsFirst(me, Limit.of(20))

        assertEquals(false, comments.any { it.id == mineOnOthersPrivate })
    }

    @Test
    fun `내 비공개 글에 단 내 댓글은 나온다`() {
        val comments = commentRepository.findMyCommentsFirst(me, Limit.of(20))

        assertEquals(true, comments.any { it.id == mineOnMyPrivate })
    }

    @Test
    fun `삭제된 댓글(톰스톤)은 빠진다`() {
        val comments = commentRepository.findMyCommentsFirst(me, Limit.of(20))

        assertEquals(false, comments.any { it.id == myTombstone })
    }

    @Test
    fun `최신순으로, 작성 시각이 같으면 id 역순으로 정렬한다`() {
        val comments = commentRepository.findMyCommentsFirst(me, Limit.of(20))

        assertEquals(listOf(mineOnMyPublic, mineOnMyPrivate, mineOnOthersPublic), comments.map { it.id })
    }

    // 다음 쪽 쿼리는 첫 쪽과 WHERE를 따로 적는다. 빠져야 할 댓글(12시)이 기준보다 뒤에 있으면 조건이 깨져도 안 드러나므로
    // 기준을 모든 댓글보다 뒤(13시)에 둔 호출로 같은 조건인지 보고, 첫 쪽 마지막 행 기준으로 이어 읽는 것도 본다
    @Test
    fun `findMyCommentsAfter 도 첫 쪽과 같은 조건으로 거르고 기준 뒤부터 이어 읽는다`() {
        val first = commentRepository.findMyCommentsFirst(me, Limit.of(2))
        val second = commentRepository.findMyCommentsAfter(me, first.last().createdAt, first.last().id, Limit.of(20))
        val fromTop = commentRepository.findMyCommentsAfter(me, at(13), UUID.randomUUID(), Limit.of(20))

        assertEquals(listOf(mineOnMyPublic, mineOnMyPrivate), first.map { it.id })
        assertEquals(listOf(mineOnOthersPublic), second.map { it.id })
        assertEquals(listOf(mineOnMyPublic, mineOnMyPrivate, mineOnOthersPublic), fromTop.map { it.id })
    }

    private fun at(hour: Int): LocalDateTime = LocalDateTime.of(2026, 10, 1, hour, 0)

    private fun insertMember(id: UUID) {
        jdbc.update("INSERT INTO members (id) VALUES (?)", id)
    }

    private fun insertPost(id: UUID, owner: UUID, isPrivate: Boolean = false) {
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', 't', ?)",
            id,
            owner,
            isPrivate,
        )
    }

    private fun insertComment(id: UUID, post: UUID, author: UUID, createdAt: LocalDateTime, isDeleted: Boolean = false) {
        jdbc.update(
            "INSERT INTO comments (id, post_id, user_id, content, is_deleted, created_at) VALUES (?, ?, ?, 'c', ?, ?)",
            id,
            post,
            author,
            isDeleted,
            createdAt,
        )
    }
}
