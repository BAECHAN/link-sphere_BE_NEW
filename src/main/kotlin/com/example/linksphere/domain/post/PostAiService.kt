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
            // 제목이 빈약하면 프롬프트에 URL 문자열을 넣어 모델을 오염시키지 않는다.
            // AI가 새 제목을 쓸지 판단하는 게이트도 이 값 하나로 통일한다.
            val titleIsWeak = WeakTitleDetector.isWeak(post.title, post.url)
            val promptTitle = if (titleIsWeak) "" else post.title

            // 요약과 카테고리 분류를 병렬로 돌린다. 카테고리 분류는 요약이 만들어낼 AI 태그
            // 대신 크롤링 시점의 기존 태그만 입력으로 쓴다 — 순차 의존을 끊어야 병렬화가 되고,
            // 태그 매칭 1차 필터는 기존 태그만으로도 대체로 충분하다.
            val summaryFuture = geminiService.analyzeContentAsync(promptTitle, description, content)
            val categoryFuture =
                if (post.categories.isEmpty()) {
                    postCategoryClassifier.classifyAsync(promptTitle, description, existingTags)
                } else {
                    null
                }

            val analysisResult = summaryFuture.join()

            val mergedTags = existingTags.toMutableList()
            if (analysisResult.tags.isNotEmpty()) {
                val newTags = analysisResult.tags.filter { !mergedTags.contains(it) }
                mergedTags.addAll(newTags)
            }

            // JS로 렌더되는 페이지(YouTube·d2.naver.com 등)는 크롤링 본문이 사실상 비어 있어
            // 모델이 SUMMARY를 빈 값으로 돌려준다. 없는 내용을 지어내게 강요하는 대신 요약만
            // 비워 두고, 같은 응답에서 얻은 태그·제목·설명·카테고리는 그대로 저장한다. 단 넷
            // 다 비었다면 그건 "본문이 없다"가 아니라 응답 자체가 실패한 것이므로(API 키 누락
            // 시 parseResponse가 전부 null을 돌려준다) FAILED로 남긴다.
            val newSummary = analysisResult.summary?.takeIf { it.isNotBlank() }
            val hasAnySignal =
                newSummary != null ||
                    mergedTags.size > existingTags.size ||
                    !analysisResult.title.isNullOrBlank() ||
                    !analysisResult.description.isNullOrBlank()
            if (!hasAnySignal) {
                throw RuntimeException("AI Analysis returned nothing usable")
            }
            if (newSummary == null) {
                logger.warn(
                    "[AI] 요약 없이 부분 저장 - postId: $postId, contentLength: ${content.length}, tags: $mergedTags",
                )
            }

            // 요약은 순수 폴백으로 둔다 — 이미 요약이 있는 글을 백필로 재분석해도 빈 요약이
            // 기존 값을 지우지 않는다(아래 제목·설명 폴백과 동일한 원칙).
            if (newSummary != null) post.aiSummary = newSummary
            post.tags = mergedTags

            // 크롤링이 건진 값이 있으면 절대 덮지 않는다 — 순수 폴백.
            if (titleIsWeak && !analysisResult.title.isNullOrBlank()) {
                post.title = analysisResult.title
            }
            if (post.description.isNullOrBlank() && !analysisResult.description.isNullOrBlank()) {
                post.description = analysisResult.description
            }

            if (categoryFuture != null) {
                post.categories.addAll(categoryFuture.join())
            }

            post.aiStatus = AiStatus.COMPLETED

            // save()만으로는 UPDATE가 실제 트랜잭션 커밋 시점(이 메서드 바깥, try/catch 밖)에
            // 지연 flush되어, 그 사이 post가 삭제됐을 때 발생하는 낙관적 락 예외를 여기서
            // 잡지 못하고 조용히 삼켜지거나(과거) 호출자에게 새는(현재 구조) 문제가 있었다.
            // saveAndFlush로 즉시 flush시켜 예외가 여기 catch 블록 범위 안에서 나게 만든다.
            postRepository.saveAndFlush(post)
            logger.info("[AI] 분석 완료 - postId: $postId, summary: ${newSummary?.take(100)}, tags: $mergedTags")
        } catch (e: ObjectOptimisticLockingFailureException) {
            // saveAndFlush가 실패하면 Hibernate 세션이 이미 오염돼(rollback-only) 이 트랜잭션은
            // 커밋할 수 없다. 여기서 삼키고 정상 리턴하면 트랜잭션 매니저가 커밋을 시도하다가
            // rollback-only를 발견해 UnexpectedRollbackException을 새로 던진다(실제로 겪음).
            // 반드시 다시 던져 정상적으로 롤백시키고, 호출자(LambdaHandler)가 "삭제로 인한
            // 정상 레이스"로 처리하게 한다.
            logger.info("[AI] 분석 중 post가 삭제됨 - postId: $postId")
            throw e
        } catch (e: Exception) {
            logger.error("[AI] 분석 실패 - postId: $postId", e)
            post.aiStatus = AiStatus.FAILED
            postRepository.saveAndFlush(post)
        }
    }
}
