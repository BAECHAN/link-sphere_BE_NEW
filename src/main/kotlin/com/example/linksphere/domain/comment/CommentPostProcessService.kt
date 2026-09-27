package com.example.linksphere.domain.comment

import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.post.PostRepository
import com.example.linksphere.domain.post.UrlMetadataExtractor
import com.example.linksphere.infra.aws.CommentJobDispatcher
import com.example.linksphere.infra.fcm.FcmNotificationService
import org.slf4j.LoggerFactory
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

// 댓글 작성/수정 요청 경로 밖에서 알림 발송과 링크 프리뷰 크롤링을 처리한다.
// CommentService가 요청 경로 안에서 이 둘을 동기 호출하면, 외부 사이트/Firebase 응답이
// 느려질 때 댓글 등록 자체가 지연되거나(크롤링 최대 ~30초) FCM 전송 실패가 댓글 INSERT를
// 롤백시킬 수 있다(FcmService가 @Transactional로 합류). PostAIService와 동일한 패턴으로 분리한다.
@Service
class CommentPostProcessService(
    private val commentRepository: CommentRepository,
    private val postRepository: PostRepository,
    private val memberRepository: MemberRepository,
    private val fcmNotificationService: FcmNotificationService,
    private val urlMetadataExtractor: UrlMetadataExtractor,
    private val commentJobDispatcher: CommentJobDispatcher,
) {

    private val logger = LoggerFactory.getLogger(CommentPostProcessService::class.java)

    // 여기서 직접 알림/크롤링을 처리하지 않는다 - 원래 요청(댓글 작성/수정)과 같은 실행 환경 안에서
    // 계속 처리하면 handleRequest() 반환 후 컨테이너가 얼어붙는 문제를 다시 겪는다.
    // 별도 Lambda 호출(CommentJobDispatcher)로 위임만 하고 즉시 리턴한다.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleCommentPostProcess(event: CommentPostProcessEvent) {
        logger.info("[Comment PostProcess] 이벤트 수신 (커밋 후), 별도 Lambda 호출로 위임 - commentId: ${event.commentId}")
        commentJobDispatcher.dispatch(event)
    }

    // CommentJobDispatcher가 위임한 별도 Lambda 호출 안에서 LambdaHandler가 직접 호출하는 실제 처리부.
    // 원래 요청과 완전히 독립된 실행 환경이므로 여기서 오래 걸려도 CloudFront/원래 응답에 영향 없다.
    @Transactional
    fun processCommentJob(event: CommentPostProcessEvent) {
        val comment = commentRepository.findByIdOrNull(event.commentId)
        if (comment == null) {
            // 후처리가 위임된 뒤(또는 처리되는 동안) 댓글이 삭제된 정상적인 레이스다.
            logger.info("[Comment PostProcess] Comment가 이미 삭제됨 - commentId: ${event.commentId}")
            return
        }

        // 알림 실패가 링크 프리뷰 갱신을 막지 않도록 서로 독립적으로 처리한다.
        if (event.notify) {
            runCatching { sendNotification(comment) }
                .onFailure { logger.error("[Comment PostProcess] 알림 발송 실패 - commentId: ${event.commentId}", it) }
        }

        if (comment.linkUrl != null) {
            updateLinkMetadata(comment)
        }
    }

    private fun sendNotification(comment: TableComment) {
        val commenter = memberRepository.findByIdOrNull(comment.userId) ?: return
        val nickname = commenter.nickname ?: "누군가"
        val contentPreview = comment.content.take(50)

        val parentId = comment.parentId
        if (parentId == null) {
            // 내 포스트에 타인이 댓글을 달면 알림 (루트 댓글)
            val post = postRepository.findByIdOrNull(comment.postId) ?: return
            fcmNotificationService.sendCommentNotification(
                postAuthorId = post.userId,
                commenterNickname = nickname,
                commentContent = contentPreview,
                postId = comment.postId,
                commentId = comment.id,
            )
        } else {
            // 내 댓글에 타인이 답글을 달면 알림
            val parent = commentRepository.findByIdOrNull(parentId) ?: return
            fcmNotificationService.sendReplyNotification(
                parentCommentAuthorId = parent.userId,
                replierNickname = nickname,
                replyContent = contentPreview,
                postId = comment.postId,
                commentId = comment.id,
            )
        }
    }

    private fun updateLinkMetadata(comment: TableComment) {
        val url = comment.linkUrl ?: return
        val meta = urlMetadataExtractor.extract(url)
        comment.linkTitle = meta.title
        comment.linkDescription = meta.description
        comment.linkOgImage = meta.ogImage
        commentRepository.save(comment)
    }
}
