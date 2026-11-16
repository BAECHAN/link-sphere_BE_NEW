package com.example.linksphere.domain.post

import java.util.UUID

/**
 * 글 응답에 싣는 댓글 수를 묻는 약속(docs/plans/2026-11-16-be-structure-stage2.md PR-8). 무엇이 필요한지는 post가 정하고
 * 구현은 댓글을 가진 comment가 한다(CommentCountAdapter) - post는 comment를 모르고, 의존은 comment → post 한 방향이다.
 */
interface PostCommentPort {

    /** 글마다 댓글 수. 댓글이 없는 글은 결과에 없다. */
    fun countByPostIds(postIds: Collection<UUID>): Map<UUID, Int>

    fun countByPostId(postId: UUID): Int
}
