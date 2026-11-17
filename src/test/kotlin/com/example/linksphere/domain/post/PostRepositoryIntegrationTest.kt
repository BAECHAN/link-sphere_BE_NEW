package com.example.linksphere.domain.post

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// 피드 조회(findPostsAfter)의 filter 조건을 실제 Postgres에서 검증한다 — 지금은 isLiked만 다룬다
@PostgresIntegrationTest
class PostRepositoryIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var postRepository: PostRepository

    private val me = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private val likedByMe = UUID.randomUUID()
    private val likedByOther = UUID.randomUUID()
    private val notLiked = UUID.randomUUID()
    private val othersPrivateLikedByMe = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        insertMember(me)
        insertMember(other)
        insertPost(likedByMe, owner = other)
        insertPost(likedByOther, owner = other)
        insertPost(notLiked, owner = other)
        insertPost(othersPrivateLikedByMe, owner = other, isPrivate = true)
        insertLike(me, likedByMe)
        insertLike(other, likedByOther)
        insertLike(me, othersPrivateLikedByMe)
    }

    // filter=isLiked·isBookmarked는 PostService가 interaction에 글 id를 물어 postIds로 넘긴다(같은 계획 PR-9b). 저장소는 id 집합으로만
    // 거른다 - filter 문자열부터 응답까지는 PostInteractionPortIntegrationTest가 본다
    @Test
    fun `postIds 를 주면 그 글만 돌려준다`() {
        val page = findPosts(postIds = setOf(likedByMe), currentUserId = me)

        assertEquals(setOf(likedByMe), page.content.map { it.id }.toSet())
    }

    // 공개 게이트(공개 글 OR 내 글)가 AND로 함께 붙는다 — id가 넘어와도 남의 비공개 글은 새지 않고, count도 같은 집합을 센다
    @Test
    fun `postIds 에 남의 비공개 글이 들어 있어도 빼고 센다`() {
        val page = findPosts(postIds = setOf(likedByMe, othersPrivateLikedByMe), currentUserId = me)

        assertEquals(1L, page.total)
        assertEquals(false, page.content.any { it.id == othersPrivateLikedByMe })
    }

    // 비로그인의 isLiked·isBookmarked는 PostService가 null로 넘긴다 — 조건을 붙이지 않아 공개 피드 전체가 나온다
    @Test
    fun `postIds 가 null 이면 거르지 않고 공개 글을 모두 돌려준다`() {
        val page = findPosts(postIds = null, currentUserId = null)

        assertEquals(setOf(likedByMe, likedByOther, notLiked), page.content.map { it.id }.toSet())
    }

    // 좋아요·북마크한 글이 없는 경우다 — "거르지 않음"으로 읽으면 전체 글이 나온다
    @Test
    fun `postIds 가 빈 집합이면 전체 글이 아니라 빈 결과이고 건수는 0이다`() {
        val page = findPosts(postIds = emptySet(), currentUserId = me)

        assertEquals(emptyList<UUID>(), page.content.map { it.id })
        assertEquals(0L, page.total)
        assertEquals(null, page.nextKeys)
    }

    // 검색은 건수 쿼리와 관련도 정렬 쿼리가 따로 나간다 - 둘 다 같은 id 조건을 본다
    @Test
    fun `검색과 postIds 를 함께 주면 건수도 그 글 가운데 검색에 걸린 것만 센다`() {
        val inSetHit = UUID.randomUUID()
        val outOfSetHit = UUID.randomUUID()
        insertPost(inSetHit, owner = other, title = "kotlin coroutine guide")
        insertPost(outOfSetHit, owner = other, title = "kotlin flow basics")

        val page =
            postRepository.findPostsAfter(
                categoryIds = null,
                search = "kotlin",
                filters = emptySet(),
                postIds = setOf(inSetHit, likedByMe),
                authorIds = null,
                excludedAuthorIds = emptySet(),
                currentUserId = me,
                sort = PostListSort.RELEVANCE,
                after = null,
                size = 20,
                withTotal = true,
            )

        assertEquals(listOf(inSetHit), page.content.map { it.id })
        assertEquals(1L, page.total)
    }

    @Test
    fun `조회수 증가는 DB 값을 1 올린다`() {
        val before = viewCountInDb(notLiked)

        postRepository.incrementViewCount(notLiked)

        assertEquals(before + 1, viewCountInDb(notLiked))
    }

    // 벌크 UPDATE는 읽어 둔 엔티티에 반영되지 않는다 — 조회 기록 응답이 쓰는 재조회는 엔티티를 읽은 뒤에도 올린 값을 돌려줘야 한다
    @Test
    fun `조회수 재조회는 엔티티를 읽어 둔 트랜잭션에서도 올린 값을 돌려준다`() {
        val before = postRepository.findById(notLiked).orElseThrow().viewCount ?: 0

        postRepository.incrementViewCount(notLiked)

        assertEquals(before + 1, postRepository.findViewCountById(notLiked))
    }

    private fun viewCountInDb(postId: UUID): Int = jdbc.queryForObject("SELECT COALESCE(view_count, 0) FROM posts WHERE id = ?", Int::class.java, postId)!!

    private fun findPosts(postIds: Set<UUID>?, currentUserId: UUID?) = postRepository.findPostsAfter(
        categoryIds = null,
        search = null,
        filters = emptySet(),
        postIds = postIds,
        authorIds = null,
        excludedAuthorIds = emptySet(),
        currentUserId = currentUserId,
        sort = PostListSort.LATEST,
        after = null,
        size = 20,
        withTotal = true,
    )

    private fun insertMember(id: UUID) {
        jdbc.update("INSERT INTO members (id) VALUES (?)", id)
    }

    private fun insertPost(id: UUID, owner: UUID, isPrivate: Boolean = false, title: String = "t") {
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private) VALUES (?, ?, 'https://example.com', ?, ?)",
            id,
            owner,
            title,
            isPrivate,
        )
    }

    private fun insertLike(user: UUID, post: UUID) {
        jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", user, post)
    }
}
