package com.example.linksphere.domain.post

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// 피드 조회(findPosts)의 filter 조건을 실제 Postgres에서 검증한다 — 지금은 isLiked만 다룬다
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

    @Test
    fun `isLiked 는 내가 좋아요한 글만 돌려준다`() {
        val page = findPosts(filter = "isLiked", currentUserId = me)

        assertEquals(setOf(likedByMe), page.content.map { it.id }.toSet())
    }

    // 공개 게이트(공개 글 OR 내 글)가 AND로 함께 붙는다 — 좋아요했어도 남의 비공개 글은 새지 않고, count도 같은 집합을 센다
    @Test
    fun `isLiked 는 내가 좋아요한 남의 비공개 글을 빼고 센다`() {
        val page = findPosts(filter = "isLiked", currentUserId = me)

        assertEquals(1, page.totalElements)
        assertEquals(false, page.content.any { it.id == othersPrivateLikedByMe })
    }

    // isBookmarked·isMyPosts와 같다 — 비로그인이면 조건을 붙이지 않아 공개 피드 전체가 나온다
    @Test
    fun `비로그인이면 isLiked 를 무시하고 공개 글을 모두 돌려준다`() {
        val page = findPosts(filter = "isLiked", currentUserId = null)

        assertEquals(setOf(likedByMe, likedByOther, notLiked), page.content.map { it.id }.toSet())
    }

    @Test
    fun `조회수 증가는 벌크 UPDATE라 이미 읽은 엔티티에는 반영되지 않고 다시 읽어야 보인다`() {
        val post = postRepository.findById(notLiked).orElseThrow()
        val before = post.viewCount ?: 0

        postRepository.incrementViewCount(notLiked)

        // 엔티티는 그대로다 - 응답을 이 엔티티로 만들면 증가 전 값이 나간다(PostService.getPostById)
        assertEquals(before, post.viewCount ?: 0)
        assertEquals(before + 1, postRepository.findViewCountById(notLiked))
    }

    private fun findPosts(filter: String, currentUserId: UUID?) = postRepository.findPosts(
        category = null,
        search = null,
        filter = filter,
        nickname = null,
        currentUserId = currentUserId,
        pageable = PageRequest.of(0, 20),
    )

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

    private fun insertLike(user: UUID, post: UUID) {
        jdbc.update("INSERT INTO post_reactions (user_id, post_id) VALUES (?, ?)", user, post)
    }
}
