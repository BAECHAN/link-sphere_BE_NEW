package com.example.linksphere.infra.fcm

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class FcmNotificationService(private val fcmService: FcmService) {

    // 닉네임·본문을 넣지 않는다 - 잠금화면 등에서 세션이 살아있는 동안에도 댓글 내용이
    // 노출되는 걸 막기 위한 의도적인 결정이다(OWASP MASTG·EFF가 공통으로 권고하는
    // "알림 내용 최소화" 패턴). 자세한 내용은 앱을 열어야만 볼 수 있다.

    // 답글 알림: 내 댓글에 누군가 답글을 달았을 때
    fun sendReplyNotification(parentCommentAuthorId: UUID, postId: UUID, commentId: UUID) {
        fcmService.sendToUser(
            userId = parentCommentAuthorId,
            title = "새로운 답글",
            body = "회원님의 댓글에 새 답글이 달렸어요.",
            data = mapOf(
                "type" to "REPLY",
                "postId" to postId.toString(),
                "commentId" to commentId.toString(),
            ),
        )
    }

    // 댓글 알림: 내 포스트에 새 댓글이 달렸을 때
    fun sendCommentNotification(postAuthorId: UUID, postId: UUID, commentId: UUID) {
        fcmService.sendToUser(
            userId = postAuthorId,
            title = "새로운 댓글",
            body = "회원님의 게시글에 새 댓글이 달렸어요.",
            data = mapOf(
                "type" to "COMMENT",
                "postId" to postId.toString(),
                "commentId" to commentId.toString(),
            ),
        )
    }
}
