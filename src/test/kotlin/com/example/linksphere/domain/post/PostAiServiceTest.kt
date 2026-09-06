package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.TableCategory
import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.infra.ai.dto.AiAnalysisResult
import com.example.linksphere.infra.aws.AiJobDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CompletableFuture

@ExtendWith(MockitoExtension::class)
class PostAiServiceTest {

    @Mock private lateinit var postRepository: PostRepository

    @Mock private lateinit var geminiService: GeminiService

    @Mock private lateinit var postCategoryClassifier: PostCategoryClassifier

    @Mock private lateinit var aiJobDispatcher: AiJobDispatcher

    @InjectMocks private lateinit var postAIService: PostAIService

    private val userId = UUID.randomUUID()

    // 카테고리를 미리 채워 postCategoryClassifier 경로(분류 폴백)를 건너뛰고
    // 이 테스트가 검증하려는 제목·설명 폴백만 격리해서 본다.
    private val category = TableCategory(id = 1L, name = "개발", slug = "dev")

    private fun post(title: String, description: String? = null) = TablePost(
        userId = userId,
        url = "https://example.com/article",
        title = title,
        description = description,
        categories = mutableSetOf(category),
    )

    private fun event(postId: UUID) = PostCreatedEvent(
        postId = postId,
        userId = userId,
        title = "제목",
        description = null,
        content = "본문 내용",
        existingTags = emptyList(),
    )

    @Test
    fun `processAiJob은 제목이 빈약하면 AI 제목으로 교체한다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "https://example.com/article")
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = "요약", tags = emptyList(), title = "AI가 지은 제목")))

        postAIService.processAiJob(event(postId))

        assertEquals("AI가 지은 제목", target.title)
    }

    @Test
    fun `processAiJob은 제목이 정상이면 AI 제목이 있어도 바꾸지 않는다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "이미 좋은 제목입니다")
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("이미 좋은 제목입니다", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = "요약", tags = emptyList(), title = "AI가 지은 제목")))

        postAIService.processAiJob(event(postId))

        assertEquals("이미 좋은 제목입니다", target.title)
    }

    @Test
    fun `processAiJob은 설명이 없으면 AI 설명으로 채운다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "이미 좋은 제목입니다", description = null)
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("이미 좋은 제목입니다", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = "요약", tags = emptyList(), description = "AI가 지은 설명")))

        postAIService.processAiJob(event(postId))

        assertEquals("AI가 지은 설명", target.description)
    }

    @Test
    fun `processAiJob은 설명이 있으면 AI 설명이 있어도 바꾸지 않는다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "이미 좋은 제목입니다", description = "크롤링된 설명")
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("이미 좋은 제목입니다", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = "요약", tags = emptyList(), description = "AI가 지은 설명")))

        postAIService.processAiJob(event(postId))

        assertEquals("크롤링된 설명", target.description)
    }

    @Test
    fun `processAiJob은 요약이 비어도 태그를 저장하고 COMPLETED로 남긴다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "이미 좋은 제목입니다")
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("이미 좋은 제목입니다", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = null, tags = listOf("AI", "학습"))))

        postAIService.processAiJob(event(postId))

        assertEquals(listOf("AI", "학습"), target.tags)
        assertEquals(AiStatus.COMPLETED, target.aiStatus)
        assertEquals(null, target.aiSummary)
    }

    @Test
    fun `processAiJob은 요약이 비어도 빈약한 제목은 AI 제목으로 교체한다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "https://example.com/article")
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = null, tags = emptyList(), title = "AI가 지은 제목")))

        postAIService.processAiJob(event(postId))

        assertEquals("AI가 지은 제목", target.title)
        assertEquals(AiStatus.COMPLETED, target.aiStatus)
    }

    @Test
    fun `processAiJob은 아무것도 못 건지면 FAILED로 남긴다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "이미 좋은 제목입니다")
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("이미 좋은 제목입니다", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = null, tags = emptyList())))
        `when`(postRepository.saveAndFlush(target)).thenReturn(target)

        postAIService.processAiJob(event(postId))

        assertEquals(AiStatus.FAILED, target.aiStatus)
    }

    @Test
    fun `processAiJob은 빈 요약으로 기존 aiSummary를 덮지 않는다`() {
        val postId = UUID.randomUUID()
        val target = post(title = "이미 좋은 제목입니다").apply { aiSummary = "기존 요약" }
        `when`(postRepository.findById(postId)).thenReturn(Optional.of(target))
        `when`(geminiService.analyzeContentAsync("이미 좋은 제목입니다", null, "본문 내용"))
            .thenReturn(CompletableFuture.completedFuture(AiAnalysisResult(summary = null, tags = listOf("새태그"))))

        postAIService.processAiJob(event(postId))

        assertEquals("기존 요약", target.aiSummary)
    }
}
