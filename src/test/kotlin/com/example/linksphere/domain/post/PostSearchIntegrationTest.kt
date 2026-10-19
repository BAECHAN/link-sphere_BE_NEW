package com.example.linksphere.domain.post

import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

// 검색 관련도 정렬(PostSearchQuery.relevanceScore)을 실제 Postgres에서 검증한다 — 단어 일치 보너스는
// regexp_replace의 부정 문자 클래스에 기대므로 Java Regex 단위 테스트만으로는 DB에서의 동작을 보장하지 못한다
@PostgresIntegrationTest
class PostSearchIntegrationTest {

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var postRepository: PostRepository

    private val owner = UUID.randomUUID()

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id) VALUES (?)", owner)
    }

    // 보너스가 없으면 react 성능(58) → Threads(10) → REA(8) 순이다. REA 글을 가장 오래된 글로 둬 동점 정렬(createdAt)이 아니라 점수로 이기는지 본다
    @Test
    fun `rea 로 검색하면 rea 를 한 단어로 담은 글이 단어 일부로만 걸린 글보다 먼저 나온다`() {
        val rea = insertPost("GitHub - morluto/rea: Reverse engineer anything", hoursAgo = 3)
        val reactPrefix = insertPost("react 성능 이슈 찾을 때", hoursAgo = 2)
        val threads = insertPost("Threads", tags = "{threads.com,Threads}", hoursAgo = 1)

        val page = search("rea")

        assertEquals(listOf(rea, reactPrefix, threads), page.content.map { it.id })
        assertEquals(3, page.totalElements)
    }

    // 한글이 단어 경계로 인식되는지(로케일과 무관하게) — 둘 다 제목이 react로 시작해 단어 일치 보너스만 다르다
    @Test
    fun `react 뒤에 한글 조사가 붙어도 단어 일치로 본다`() {
        val withParticle = insertPost("React로 만든 대시보드", hoursAgo = 2)
        val reactive = insertPost("reactive 프로그래밍 입문", hoursAgo = 1)

        assertEquals(listOf(withParticle, reactive), search("react").content.map { it.id })
    }

    @Test
    fun `태그 하나가 검색어와 같으면 단어 일치로 본다`() {
        val tagged = insertPost("제목 하나", tags = "{github.com,rea,web}", hoursAgo = 2)
        val partial = insertPost("제목 둘", tags = "{threads}", hoursAgo = 1)

        assertEquals(listOf(tagged, partial), search("rea").content.map { it.id })
    }

    // 한글 토큰에는 보너스를 주지 않는다 — 점수가 같아 기존처럼 최신 글이 먼저 나온다
    @Test
    fun `한글 검색어는 단어 일치 보너스 없이 기존 순서를 유지한다`() {
        val spaced = insertPost("리액트 상태관리", hoursAgo = 2)
        val withParticle = insertPost("리액트를 쓰는 이유", hoursAgo = 1)

        assertEquals(listOf(withParticle, spaced), search("리액트").content.map { it.id })
    }

    private fun search(keyword: String) = postRepository.findPosts(
        category = null,
        search = keyword,
        filter = null,
        nickname = null,
        currentUserId = null,
        pageable = PageRequest.of(0, 20),
    )

    private fun insertPost(title: String, tags: String? = null, hoursAgo: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, tags, created_at) VALUES (?, ?, 'https://example.com', ?, CAST(? AS text[]), now() - make_interval(hours => ?))",
            id,
            owner,
            title,
            tags,
            hoursAgo,
        )
        return id
    }
}
