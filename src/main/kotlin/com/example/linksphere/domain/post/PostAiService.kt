package com.example.linksphere.domain.post

import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.infra.sse.SseEmitterService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class PostAIService(
        private val postRepository: PostRepository,
        private val geminiService: GeminiService,
        private val sseEmitterService: SseEmitterService
) {

    private val logger = LoggerFactory.getLogger(PostAIService::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handlePostCreatedEvent(event: PostCreatedEvent) {
        val postId = event.postId
        val userId = event.userId
        val title = event.title
        val description = event.description
        val content = event.content
        val existingTags = event.existingTags

        logger.info("[AI Async] 이벤트 수신 (커밋 후) - postId: $postId")

        val post = postRepository.findById(postId).orElse(null)
        if (post == null) {
            logger.error("[AI Async] Post를 찾을 수 없음 - postId: $postId")
            return
        }

        try {
            val analysisResult = geminiService.analyzeContent(title, description, content)

            if (analysisResult.summary.isNullOrBlank()) {
                throw RuntimeException("AI Analysis returned empty summary")
            }

            // 기존 tags에 AI 태그 추가 (중복 제거)
            val mergedTags = existingTags.toMutableList()
            if (analysisResult.tags.isNotEmpty()) {
                val newTags = analysisResult.tags.filter { !mergedTags.contains(it) }
                mergedTags.addAll(newTags)
            }

            post.aiSummary = analysisResult.summary
            post.tags = mergedTags
            post.aiStatus = AiStatus.COMPLETED

            postRepository.save(post)
            logger.info(
                    "[AI Async] 분석 완료 - postId: $postId, summary: ${analysisResult.summary?.take(100)}, tags: $mergedTags"
            )

            // SSE로 프론트엔드에 완료 알림
            sseEmitterService.sendToUser(
                    userId,
                    "ai-complete",
                    mapOf(
                            "postId" to postId.toString(),
                            "aiStatus" to "COMPLETED",
                            "aiSummary" to (analysisResult.summary ?: ""),
                            "tags" to mergedTags
                    )
            )
        } catch (e: Exception) {
            logger.error("[AI Async] 분석 실패 - postId: $postId", e)

            post.aiStatus = AiStatus.FAILED
            postRepository.save(post)

            sseEmitterService.sendToUser(
                    userId,
                    "ai-complete",
                    mapOf("postId" to postId.toString(), "aiStatus" to "FAILED")
            )
        }
    }
}
