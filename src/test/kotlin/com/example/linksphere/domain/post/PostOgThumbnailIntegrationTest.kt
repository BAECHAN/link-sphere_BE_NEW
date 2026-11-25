package com.example.linksphere.domain.post

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// posts.og_image_thumbnail을 쓰고 읽는 쿼리를 실제 Postgres에서 본다(사본 잡·소급 잡·글 수정이 쓴다)
@PostgresIntegrationTest
class PostOgThumbnailIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var postRepository: PostRepository

    @Autowired private lateinit var entityManager: TestEntityManager

    private val owner = UUID.randomUUID()

    // 다른 통합 테스트가 커밋한 글이 남아 있어도 섞이지 않게 id 공간의 맨 끝을 쓴다(무작위 UUID가 여기 걸릴 일은 사실상 없다)
    private val before = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0000")
    private val needsCopy1 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0001")
    private val hasCopy = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0002")
    private val noOgImage = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0003")
    private val blankOgImage = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0004")
    private val needsCopy2 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0005")
    private val needsCopy3 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffff0006")

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", owner)
        insertPost(needsCopy3, ogImage = "https://example.com/6.png")
        insertPost(needsCopy1, ogImage = "https://example.com/1.png")
        insertPost(hasCopy, ogImage = "https://example.com/2.png", thumbnail = "https://linksphere.click/og-thumbnails/2.jpg")
        insertPost(noOgImage, ogImage = null)
        insertPost(blankOgImage, ogImage = "")
        insertPost(needsCopy2, ogImage = "https://example.com/5.png")
    }

    @Test
    fun `원본 주소가 그대로일 때만 사본 주소를 채운다`() {
        val filled = postRepository.updateOgImageThumbnailIfOgImage(needsCopy1, "https://example.com/1.png", "https://linksphere.click/og-thumbnails/1.jpg")
        val stale = postRepository.updateOgImageThumbnailIfOgImage(needsCopy2, "https://example.com/old.png", "https://linksphere.click/og-thumbnails/old.jpg")

        assertEquals(1, filled)
        assertEquals(0, stale)
        assertEquals("https://linksphere.click/og-thumbnails/1.jpg", thumbnailOf(needsCopy1))
        assertEquals(null, thumbnailOf(needsCopy2))
    }

    @Test
    fun `clearOgImageThumbnail은 사본 주소를 비운다`() {
        postRepository.clearOgImageThumbnail(hasCopy)

        assertEquals(null, thumbnailOf(hasCopy))
    }

    // AI 잡(PostAIService.processAiJob)은 글을 읽어 전체를 save한다. 그 사이 사본 잡이 채운 컬럼을 옛 값(null)으로 덮으면 안 된다
    @Test
    fun `사본 주소는 엔티티로 읽히고 엔티티 저장으로는 덮이지 않는다`() {
        val post = postRepository.findById(hasCopy).orElseThrow()
        assertEquals("https://linksphere.click/og-thumbnails/2.jpg", post.ogImageThumbnail)

        post.title = "바뀐 제목"
        post.ogImageThumbnail = null
        postRepository.saveAndFlush(post)
        entityManager.clear()

        assertEquals("바뀐 제목", jdbc.queryForObject("SELECT title FROM posts WHERE id = ?", String::class.java, hasCopy))
        assertEquals("https://linksphere.click/og-thumbnails/2.jpg", thumbnailOf(hasCopy))
    }

    @Test
    fun `소급 대상은 og 이미지가 있고 사본이 없는 글을 id 순으로 cursor 뒤부터 건수만큼 읽는다`() {
        val firstPage = postRepository.findOgThumbnailCandidatesAfter(before, Limit.of(2))
        val nextPage = postRepository.findOgThumbnailCandidatesAfter(needsCopy2, Limit.of(10))

        assertEquals(listOf(needsCopy1 to "https://example.com/1.png", needsCopy2 to "https://example.com/5.png"), firstPage.map { it.id to it.ogImage })
        assertEquals(listOf(needsCopy3), nextPage.map { it.id })
    }

    @Test
    fun `남은 소급 대상 수는 cursor 뒤의 대상만 센다`() {
        assertEquals(3L, postRepository.countOgThumbnailCandidatesAfter(before))
        assertEquals(2L, postRepository.countOgThumbnailCandidatesAfter(needsCopy1))
        assertEquals(0L, postRepository.countOgThumbnailCandidatesAfter(needsCopy3))
    }

    private fun insertPost(id: UUID, ogImage: String?, thumbnail: String? = null) {
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, og_image, og_image_thumbnail) VALUES (?, ?, 'https://example.com', '제목', ?, ?)",
            id,
            owner,
            ogImage,
            thumbnail,
        )
    }

    private fun thumbnailOf(id: UUID): String? = jdbc.queryForObject("SELECT og_image_thumbnail FROM posts WHERE id = ?", String::class.java, id)
}
