package com.example.linksphere.domain.feed

import com.example.linksphere.domain.post.PostCreateRequest
import com.example.linksphere.domain.post.PostService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 피드 항목 하나를 봇 게시글로 등록한다. FeedCrawlService가 항목마다 이 빈을 통해 호출해야
 * @Transactional이 실제로 적용된다 — 같은 클래스 안에서 this.processFeedItem(...)으로 부르면
 * Spring 프록시를 우회해 트랜잭션 경계가 무시되고, 한 항목의 실패가 세션을 오염시켜
 * 나머지 항목까지 연쇄로 실패할 수 있다(다른 빈에서 호출해야 하는 이유는 GeminiService의
 * @Async와 동일 — "다른 빈에서 호출해야 프록시 적용" 주석 참고).
 */
@Service
class FeedItemProcessor(
    private val feedItemRepository: FeedItemRepository,
    private val postService: PostService,
) {

    private val logger = LoggerFactory.getLogger(FeedItemProcessor::class.java)

    @Transactional
    fun processFeedItem(botId: UUID, item: FeedCrawlItem) {
        val normalizedUrl = FeedUrlNormalizer.normalize(item.url)
        val itemId = UUID.randomUUID()

        // 2차 방어(정합성): Stage A의 사전 필터를 통과했어도 Lambda EVENT 호출의 자동 재시도(최대 2회)나
        // 같은 URL을 내보내는 다른 피드가 겹칠 수 있다. INSERT ... ON CONFLICT DO NOTHING이 최종
        // 방어선이다 - BookmarkRepository.insertIgnoreConflict와 동일한 형태.
        val claimed = feedItemRepository.claim(itemId, item.sourceId, null, normalizedUrl)
        if (claimed == 0) {
            logger.info("[FeedCrawl] 이미 처리된 URL - skip: ${item.url}")
            return
        }

        // 실패하면(SSRF 검증 탈락 포함) 이 트랜잭션 전체가 롤백되어 위 claim insert도 함께 사라진다.
        // 즉 원장에 남지 않으므로 해당 URL은 다음 실행에서 다시 후보가 된다(유실이 아니라 지연).
        val post =
            postService.createPost(
                botId,
                PostCreateRequest(url = item.url, title = item.title, isPrivate = false),
                fallbackContent = item.content,
            )
        feedItemRepository.attachPost(itemId, post.id)
        logger.info("[FeedCrawl] 봇 게시글 등록 완료 - postId: ${post.id}, url: ${item.url}")
    }
}
