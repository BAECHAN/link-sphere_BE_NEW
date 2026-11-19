package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentCountAdapter
import com.example.linksphere.domain.comment.CommentPostEventListener
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.domain.interaction.PostInteractionAdapter
import com.example.linksphere.domain.linkpreview.LinkPreviewService
import com.example.linksphere.domain.linkpreview.SafeUrlValidator
import com.example.linksphere.domain.linkpreview.UrlMetadataExtractor
import com.example.linksphere.domain.member.MemberService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.ratelimit.RateLimitService
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * 글 목록의 닉네임·봇 제외 필터를 실제 Postgres에서 본다(docs/plans/2026-11-16-be-structure-stage2.md PR-7b).
 *
 * 예전에는 글 쿼리 한 문장이 회원 테이블을 서브쿼리로 읽었다. 지금은 PostService가 member에 회원 id를 묻고 글 쿼리에는
 * id 집합만 넘긴다 - 두 문장으로 나뉘어도 결과(대소문자 무시 부분 일치, 탈퇴 신청 중인 회원 제외, 쪽 경계)가 같은지 확인한다.
 */
@PostgresIntegrationTest
@Import(PostService::class, PostSearchFlow::class, PostResponseAssembler::class, PostInteractionAdapter::class, CommentService::class, CommentCountAdapter::class, CommentPostEventListener::class, PostLookupService::class, PostLinkParser::class, CategoryService::class, MemberService::class)
class PostAuthorFilterIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var advisoryLock: AdvisoryLock

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    @MockitoBean private lateinit var supabaseStorageService: SupabaseStorageService

    private val devKim = UUID.randomUUID()
    private val kimchi = UUID.randomUUID()
    private val pendingKim = UUID.randomUUID()
    private val lee = UUID.randomUUID()
    private val bot = UUID.randomUUID()

    private lateinit var devKimOld: UUID
    private lateinit var devKimNew: UUID
    private lateinit var kimchiPost: UUID
    private lateinit var pendingKimPost: UUID
    private lateinit var leePost: UUID
    private lateinit var botPost: UUID

    @BeforeEach
    fun seed() {
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'DevKim')", devKim)
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'kimchi')", kimchi)
        // 탈퇴 신청 중 - 닉네임은 남아 있지만 남에게는 탈퇴한 것처럼 보여야 한다
        jdbc.update("INSERT INTO members (id, nickname, deletion_requested_at) VALUES (?, 'kimpending', now())", pendingKim)
        jdbc.update("INSERT INTO members (id, nickname) VALUES (?, 'lee')", lee)
        jdbc.update("INSERT INTO members (id, nickname, is_bot) VALUES (?, 'kimbot', true)", bot)

        devKimOld = insertPost(devKim, hoursAgo = 6)
        kimchiPost = insertPost(kimchi, hoursAgo = 5)
        pendingKimPost = insertPost(pendingKim, hoursAgo = 4)
        leePost = insertPost(lee, hoursAgo = 3)
        botPost = insertPost(bot, hoursAgo = 2)
        devKimNew = insertPost(devKim, hoursAgo = 1)
    }

    @Test
    fun `닉네임 필터는 대소문자를 가리지 않는 부분 일치로 그 회원들의 글만 최신순으로 돌려준다`() {
        val page = feed(nickname = "KIM")

        assertEquals(listOf(devKimNew, botPost, kimchiPost, devKimOld), page.content.map { it.id })
    }

    @Test
    fun `탈퇴 신청 중인 회원은 닉네임이 남아 있어도 닉네임 필터에 걸리지 않는다`() {
        assertEquals(emptyList<UUID>(), feed(nickname = "kimpending").content.map { it.id })
    }

    // 조건을 빼 버리면 전체 글이 나온다 - 맞는 회원이 없으면 결과가 없어야 한다
    @Test
    fun `닉네임에 맞는 회원이 없으면 글을 하나도 돌려주지 않는다`() {
        val page = feed(nickname = "nobody")

        assertEquals(emptyList<UUID>(), page.content.map { it.id })
        assertNull(page.nextCursor)
    }

    @Test
    fun `봇 제외 필터는 봇이 쓴 글만 뺀다`() {
        val page = feed(filter = "excludeBots")

        assertEquals(listOf(devKimNew, leePost, pendingKimPost, kimchiPost, devKimOld), page.content.map { it.id })
    }

    @Test
    fun `봇 회원이 없으면 봇 제외 필터는 아무 글도 빼지 않는다`() {
        jdbc.update("UPDATE members SET is_bot = false WHERE id = ?", bot)

        assertEquals(6, feed(filter = "excludeBots").content.size)
    }

    // 닉네임과 봇 제외를 함께 걸고 쪽을 나눠 읽어도 빠지거나 겹치는 글이 없다
    @Test
    fun `닉네임과 봇 제외를 함께 건 목록은 쪽을 넘겨도 같은 글 집합을 순서대로 돌려준다`() {
        val first = feed(nickname = "kim", filter = "excludeBots", size = 2)
        val second = feed(nickname = "kim", filter = "excludeBots", size = 2, cursor = first.nextCursor)

        assertEquals(listOf(devKimNew, kimchiPost), first.content.map { it.id })
        assertNotNull(first.nextCursor)
        assertEquals(listOf(devKimOld), second.content.map { it.id })
        assertNull(second.nextCursor)
    }

    private fun feed(nickname: String? = null, filter: String? = null, size: Int = 20, cursor: String? = null) = postService.getPostsByCursor(null, null, filter, nickname, cursor, size, null)

    private fun insertPost(owner: UUID, hoursAgo: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO posts (id, user_id, url, title, is_private, created_at) VALUES (?, ?, 'https://example.com', 't', false, now() - make_interval(hours => ?))",
            id,
            owner,
            hoursAgo,
        )
        return id
    }
}
