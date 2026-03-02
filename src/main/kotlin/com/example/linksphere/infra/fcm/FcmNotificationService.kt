package com.example.linksphere.infra.fcm

import java.util.UUID
import org.springframework.stereotype.Service

@Service
class FcmNotificationService(private val fcmService: FcmService) {

    // 답글 알림: 내 댓글에 누군가 답글을 달았을 때
    fun sendReplyNotification(
        parentCommentAuthorId: UUID,
        replierNickname: String,
        replyContent: String,
        postId: UUID,
        commentId: UUID
    ) {
        fcmService.sendToUser(
            userId = parentCommentAuthorId,
            title = "새로운 답글",
            body = "$replierNickname: $replyContent",
            data = mapOf(
                "type" to "REPLY",
                "postId" to postId.toString(),
                "commentId" to commentId.toString()
            )
        )
    }

    // 댓글 알림: 내 포스트에 새 댓글이 달렸을 때
    fun sendCommentNotification(
        postAuthorId: UUID,
        commenterNickname: String,
        commentContent: String,
        postId: UUID,
        commentId: UUID
    ) {
        fcmService.sendToUser(
            userId = postAuthorId,
            title = "새로운 댓글",
            body = "$commenterNickname: $commentContent",
            data = mapOf(
                "type" to "COMMENT",
                "postId" to postId.toString(),
                "commentId" to commentId.toString()
            )
        )
    }
}
