package com.example.linksphere.domain.post

import com.example.linksphere.infra.ai.GeminiService
import com.example.linksphere.infra.aws.AiJobDispatcher
import org.slf4j.LoggerFactory
import org.springframework.orm.ObjectOptimisticLockingFailureException
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
            // AI 작업이 위임된 뒤(또는 처리되는 동안) 사용자가 post를 삭제한 정상적인 레이스다.
            logger.info("[AI] Post가 이미 삭제됨 - postId: $postId")
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

            // save()만으로는 UPDATE가 실제 트랜잭션 커밋 시점(이 메서드 바깥, try/catch 밖)에
            // 지연 flush되어, 그 사이 post가 삭제됐을 때 발생하는 낙관적 락 예외를 여기서
            // 잡지 못하고 조용히 삼켜지거나(과거) 호출자에게 새는(현재 구조) 문제가 있었다.
            // saveAndFlush로 즉시 flush시켜 예외가 여기 catch 블록 범위 안에서 나게 만든다.
            postRepository.saveAndFlush(post)
            logger.info("[AI] 분석 완료 - postId: $postId, summary: ${analysisResult.summary.take(100)}, tags: $mergedTags")
        } catch (e: ObjectOptimisticLockingFailureException) {
            // 분석 도중 사용자가 post를 삭제한 정상적인 레이스다 — 재시도할 필요 없는 에러.
            logger.info("[AI] 분석 완료 전에 post가 삭제됨 - postId: $postId")
        } catch (e: Exception) {
            logger.error("[AI] 분석 실패 - postId: $postId", e)
            try {
                post.aiStatus = AiStatus.FAILED
                postRepository.saveAndFlush(post)
            } catch (raceEx: ObjectOptimisticLockingFailureException) {
                logger.info("[AI] 실패 상태 저장 전에 post가 삭제됨 - postId: $postId")
            }
        }
    }
}
