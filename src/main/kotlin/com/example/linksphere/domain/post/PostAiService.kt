package com.example.linksphere.domain.post

import com.example.linksphere.infra.ai.GeminiService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class PostAIService(
        private val postRepository: PostRepository,
        private val geminiService: GeminiService
) {

    private val logger = LoggerFactory.getLogger(PostAIService::class.java)

    // 동기 처리: POST /post 응답 전에 AI 분석을 완료한다.
    // 결과는 DB에 저장되므로 프론트엔드는 GET /post/{id}로 확인한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handlePostCreatedEvent(event: PostCreatedEvent) {
        val postId = event.postId
        val title = event.title
        val description = event.description
        val content = event.content
        val existingTags = event.existingTags

        logger.info("[AI] 이벤트 수신 (커밋 후) - postId: $postId")

        val post = postRepository.findById(postId).orElse(null)
        if (post == null) {
            logger.error("[AI] Post를 찾을 수 없음 - postId: $postId")
            return
        }

        try {
            val analysisResult = geminiService.analyzeContent(title, description, content)

            if (analysisResult.summary.isNullOrBlank()) {
                throw RuntimeException("AI Analysis returned empty summary")
            }

            val mergedTags = existingTags.toMutableList()
            if (analysisResult.tags.isNotEmpty()) {
                val newTags = analysisResult.tags.filter { !mergedTags.contains(it) }
                mergedTags.addAll(newTags)
            }

            post.aiSummary = analysisResult.summary
            post.tags = mergedTags
            post.aiStatus = AiStatus.COMPLETED

            postRepository.save(post)
            logger.info("[AI] 분석 완료 - postId: $postId, summary: ${analysisResult.summary.take(100)}, tags: $mergedTags")
        } catch (e: Exception) {
            logger.error("[AI] 분석 실패 - postId: $postId", e)
            post.aiStatus = AiStatus.FAILED
            postRepository.save(post)
        }
    }
}
