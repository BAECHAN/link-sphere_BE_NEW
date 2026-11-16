package com.example.linksphere.domain.comment

import com.example.linksphere.domain.post.PostDeletingEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 글이 지워지기 직전에 그 글의 댓글에 든 스토리지 이미지를 정리한다(post의 PostLifecycleEvents.kt). 댓글 행과 댓글
 * 좋아요는 DB의 ON DELETE CASCADE가 지우므로 여기서 지우지 않는다.
 * 발행한 쪽의 트랜잭션 안에서 실행돼야 해서 트랜잭션이 없으면 실패한다(MANDATORY) - 파일 삭제는 그 트랜잭션의 커밋 뒤로 미뤄진다.
 */
@Component
class CommentPostEventListener(private val commentService: CommentService) {

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onDeleting(event: PostDeletingEvent) {
        commentService.deleteImagesForPost(event.postId)
    }
}
