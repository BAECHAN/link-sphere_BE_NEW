package com.example.linksphere.domain.comment

import com.example.linksphere.domain.post.PostCommentPort
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * post가 정한 약속(PostCommentPort)을 comment가 구현한다 - 글 응답에 싣는 댓글 수를 센다. 트랜잭션은 스스로 열지 않고
 * 호출부(PostResponseAssembler를 부른 Service)의 것을 쓴다.
 *
 * CommentService가 구현하지 않는 이유: CommentService는 글을 PostLookupService에 묻는다. post의 조립기가 이 약속으로
 * CommentService를 주입받으면 빈이 서로 물릴 수 있어 저장소만 참조하는 구현을 따로 둔다.
 */
@Component
class CommentCountAdapter(private val commentRepository: CommentRepository) : PostCommentPort {

    // 개수는 DB에서 GROUP BY로 센다(docs/BE-ARCHITECTURE.md §7-8)
    override fun countByPostIds(postIds: Collection<UUID>): Map<UUID, Int> = commentRepository.countByPostIdIn(postIds.toList()).associate { it.postId to it.count.toInt() }

    override fun countByPostId(postId: UUID): Int = commentRepository.countByPostId(postId).toInt()
}
