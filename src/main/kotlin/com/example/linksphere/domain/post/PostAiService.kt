package com.example.linksphere.domain.post

import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.infra.aws.AiJobDispatcher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Service
class PostAIService(
    private val postRepository: PostRepository,
    private val geminiService: GeminiService,
    private val postCategoryClassifier: PostCategoryClassifier,
    private val aiJobDispatcher: AiJobDispatcher,
) {

    private val logger = LoggerFactory.getLogger(PostAIService::class.java)

    // 여기서 직접 Gemini를 호출하지 않는다 — 원래 요청(POST /post)과 같은 실행 환경 안에서
    // 계속 처리하면 handleRequest() 반환 후 컨테이너가 얼어붙는 문제를 다시 겪는다.
    // 별도 Lambda 호출(AiJobDispatcher)로 위임만 하고 즉시 리턴한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handlePostCreatedEvent(event: PostCreatedEvent) {
        logger.info("[AI] 이벤트 수신 (커밋 후), 별도 Lambda 호출로 위임 - postId: ${event.postId}")
        aiJobDispatcher.dispatch(event)
    }

    // AiJobDispatcher가 위임한 별도 Lambda 호출 안에서 LambdaHandler가 직접 호출하는 실제 처리부.
    // 원래 요청과 완전히 독립된 실행 환경이므로 여기서 오래 걸려도 CloudFront/원래 응답에 영향 없다.
    @Transactional
    fun processAiJob(event: PostCreatedEvent) {
        val postId = event.postId
        val title = event.title
        val description = event.description
        val content = event.content
        val existingTags = event.existingTags

        val post = postRepository.findById(postId).orElse(null)
        if (post == null) {
            logger.error("[AI] Post를 찾을 수 없음 - postId: $postId")
            return
        }

        try {
            // 요약과 카테고리 분류를 병렬로 돌린다. 카테고리 분류는 요약이 만들어낼 AI 태그
            // 대신 크롤링 시점의 기존 태그만 입력으로 쓴다 — 순차 의존을 끊어야 병렬화가 되고,
            // 태그 매칭 1차 필터는 기존 태그만으로도 대체로 충분하다.
            val summaryFuture = geminiService.analyzeContentAsync(title, description, content)
            val categoryFuture =
                if (post.categories.isEmpty()) {
                    postCategoryClassifier.classifyAsync(title, description, existingTags)
                } else {
                    null
                }

            val analysisResult = summaryFuture.join()
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

            if (categoryFuture != null) {
                post.categories.addAll(categoryFuture.join())
            }

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
