package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryService
import com.example.linksphere.domain.comment.CommentService
import com.example.linksphere.global.common.AdvisoryLock
import com.example.linksphere.global.common.RateLimitService
import com.example.linksphere.global.exception.DuplicatePostException
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.infra.aws.AiJobDispatcher
import com.example.linksphere.support.PostgresIntegrationTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 글 등록의 중복 방지를 실제 Postgres로 본다(docs/plans/2026-11-04-idempotent-create.md). 유니크 인덱스 대기·위반, advisory
// lock, 커밋 뒤에만 나가는 AI 이벤트는 mock으로 재현되지 않는다. 커밋이 겹쳐야 하므로 테스트 트랜잭션 밖에서 돌고 끝나면 지운다.
// 컨트롤러를 그대로 불러 사전 조회 → 등록 → 위반 뒤 재조회의 트랜잭션 경계가 운영과 같게 한다.
@PostgresIntegrationTest
@Import(PostService::class, PostAIService::class, PostResponseAssembler::class, AdvisoryLock::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PostIdempotencyIntegrationTest {

    @Autowired private lateinit var postService: PostService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var urlMetadataExtractor: UrlMetadataExtractor

    @MockitoBean private lateinit var safeUrlValidator: SafeUrlValidator

    @MockitoBean private lateinit var linkPreviewService: LinkPreviewService

    @MockitoBean private lateinit var categoryService: CategoryService

    @MockitoBean private lateinit var commentService: CommentService

    @MockitoBean private lateinit var geminiService: GeminiService

    @MockitoBean private lateinit var postCategoryClassifier: PostCategoryClassifier

    @MockitoBean private lateinit var aiJobDispatcher: AiJobDispatcher

    @MockitoBean private lateinit var rateLimitService: RateLimitService

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val url = "https://example.com/article"
    private val metadata = UrlMetadata(title = "제목", description = null, ogImage = null, tags = emptyList(), pageContent = "본문")
    private val executor = Executors.newFixedThreadPool(2)

    private val controller by lazy { PostController(postService, rateLimitService) }

    @BeforeEach
    fun seed() {
        listOf(userId, otherUserId).forEach {
            jdbc.update("INSERT INTO members (id, email, nickname, password) VALUES (?, ?, ?, 'enc')", it, "$it@example.com", it.toString())
        }
        `when`(urlMetadataExtractor.extract(anyString())).thenReturn(metadata)
    }

    @AfterEach
    fun cleanUp() {
        executor.shutdownNow()
        // bookmarks.user_id는 members로 cascade되지 않아 먼저 지운다
        jdbc.update("DELETE FROM bookmarks WHERE user_id IN (?, ?)", userId, otherUserId)
        jdbc.update("DELETE FROM members WHERE id IN (?, ?)", userId, otherUserId)
    }

    @Test
    fun `같은 키로 겹친 같은 URL은 뒤 요청이 잠금에서 기다렸다가 같은 글을 받고 크롤링하지 않는다`() {
        val key = UUID.randomUUID()
        val crawling = CountDownLatch(1)
        doAnswer {
            crawling.countDown()
            // 뒤 요청이 사전 조회를 통과해 잠금에서 기다리는 동안 커밋을 늦춘다
            Thread.sleep(500)
            metadata
        }.`when`(urlMetadataExtractor).extract(url)

        val first = executor.submit<UUID> { create(key, url) }
        crawling.await(10, TimeUnit.SECONDS)
        val second = executor.submit<UUID> { create(key, url) }

        assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
        assertEquals(1, countPosts())
        verify(urlMetadataExtractor, times(1)).extract(url)
        verify(aiJobDispatcher, times(1)).dispatch(anyNonNull())
    }

    // 내용이 달라 잠금을 비켜 가면 유니크 인덱스가 막는다. bookmark면 flush에서, 아니면 커밋에서 위반이 난다 - 어느 쪽이든 트랜잭션
    // 밖에서 잡혀 이긴 쪽 글을 돌려주고, 진 쪽의 AI 이벤트는 나가지 않아야 한다.
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `같은 키가 잠금을 비켜 겹치면 유니크 위반 뒤 이긴 쪽 글을 돌려준다`(bookmark: Boolean) {
        val key = UUID.randomUUID()
        val bothCrawling = CyclicBarrier(2)
        doAnswer {
            bothCrawling.await(10, TimeUnit.SECONDS)
            metadata
        }.`when`(urlMetadataExtractor).extract(anyString())

        val first = executor.submit<UUID> { create(key, "$url/a", bookmark = bookmark) }
        val second = executor.submit<UUID> { create(key, "$url/b", bookmark = bookmark) }

        assertEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
        assertEquals(1, countPosts())
        verify(aiJobDispatcher, times(1)).dispatch(anyNonNull())
    }

    @Test
    fun `다른 키로 겹친 같은 URL은 1행만 저장하고 뒤 요청은 DuplicatePostException이다`() {
        val crawling = CountDownLatch(1)
        doAnswer {
            crawling.countDown()
            Thread.sleep(500)
            metadata
        }.`when`(urlMetadataExtractor).extract(url)

        val first = executor.submit<UUID> { create(UUID.randomUUID(), url) }
        crawling.await(10, TimeUnit.SECONDS)
        val second = executor.submit<UUID> { create(UUID.randomUUID(), url) }

        first.get(15, TimeUnit.SECONDS)
        val failure = assertThrows(ExecutionException::class.java) { second.get(15, TimeUnit.SECONDS) }
        assertInstanceOf(DuplicatePostException::class.java, failure.cause)
        assertEquals(1, countPosts())
        verify(urlMetadataExtractor, times(1)).extract(url)
    }

    @Test
    fun `완료 뒤 재전송은 크롤링·AI·한도 소모 없이 처음 글을 돌려준다`() {
        val key = UUID.randomUUID()
        val firstId = create(key, url)

        val resentId = create(key, url)

        assertEquals(firstId, resentId)
        assertEquals(1, countPosts())
        verify(urlMetadataExtractor, times(1)).extract(url)
        verify(aiJobDispatcher, times(1)).dispatch(anyNonNull())
        verify(rateLimitService, times(1)).consume(anyString(), anyInt(), anyNonNull())
    }

    @Test
    fun `다른 회원의 같은 키는 별개의 글이다`() {
        val key = UUID.randomUUID()

        assertNotEquals(create(key, url), create(key, url, member = otherUserId))
        assertEquals(1, countPosts(userId))
        assertEquals(1, countPosts(otherUserId))
    }

    @Test
    fun `키가 null인 봇 경로는 같은 URL로 여러 행을 만들 수 있고 시간 창을 보지 않는다`() {
        postService.createPost(userId, PostCreateRequest(url = url))
        postService.createPost(userId, PostCreateRequest(url = url))

        assertEquals(2, countPosts())
    }

    @Test
    fun `다른 키로 1시간 안에 같은 URL을 다시 등록하면 크롤링도 AI도 없이 막힌다`() {
        create(UUID.randomUUID(), url)

        assertThrows(DuplicatePostException::class.java) { create(UUID.randomUUID(), url) }

        assertEquals(1, countPosts())
        verify(urlMetadataExtractor, times(1)).extract(url)
        verify(aiJobDispatcher, times(1)).dispatch(anyNonNull())
    }

    @Test
    fun `1시간이 지나면 같은 URL을 다시 등록할 수 있다`() {
        create(UUID.randomUUID(), url)
        jdbc.update("UPDATE posts SET created_at = created_at - interval '61 minutes' WHERE user_id = ?", userId)

        create(UUID.randomUUID(), url)

        assertEquals(2, countPosts())
    }

    @Test
    fun `글을 지우면 같은 URL을 바로 다시 등록할 수 있다`() {
        val postId = create(UUID.randomUUID(), url)
        jdbc.update("DELETE FROM posts WHERE id = ?", postId)

        create(UUID.randomUUID(), url)

        assertEquals(1, countPosts())
    }

    @Test
    fun `다른 회원의 같은 URL은 시간 창에 걸리지 않는다`() {
        create(UUID.randomUUID(), url)

        create(UUID.randomUUID(), url, member = otherUserId)

        assertEquals(1, countPosts(otherUserId))
    }

    @Test
    fun `글을 수정해도 키는 그대로다`() {
        val key = UUID.randomUUID()
        val postId = create(key, url)

        postService.updatePost(postId, userId, PostUpdateRequest(title = "새 제목"))

        assertEquals(key, jdbc.queryForObject("SELECT idempotency_key FROM posts WHERE id = ?", UUID::class.java, postId))
    }

    private fun create(key: UUID, url: String, bookmark: Boolean = false, member: UUID = userId): UUID = controller
        .createPost(key, PostCreateRequest(url = url, bookmark = bookmark), UsernamePasswordAuthenticationToken(member.toString(), null))
        .data
        .id

    private fun countPosts(member: UUID = userId): Int = jdbc.queryForObject("SELECT COUNT(*) FROM posts WHERE user_id = ?", Int::class.java, member)!!

    private fun <T> anyNonNull(): T {
        ArgumentMatchers.any<T>()
        @Suppress("UNCHECKED_CAST")
        return null as T
    }
}
