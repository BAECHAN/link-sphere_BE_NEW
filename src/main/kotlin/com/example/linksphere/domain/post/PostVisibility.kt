package com.example.linksphere.domain.post

import com.example.linksphere.global.exception.PostNotFoundException
import java.util.UUID

/**
 * 비공개 글은 작성자에게만 보인다. 남에게는 403이 아니라 "없음"(404 POST_NOT_FOUND)으로 답해 존재 자체를 숨긴다
 * (docs/BE-ARCHITECTURE.md §7-10). 상세 조회·댓글 목록과 작성·좋아요·북마크가 모두 이 규칙을 쓴다 — 목록 쿼리는 같은 조건을
 * SQL로 건다(PostRepositoryImpl·BookmarkRepositoryImpl·CommentRepository).
 */
object PostVisibility {

    // viewerId가 null이면 비로그인 — 비공개 글은 보이지 않는다. notFoundId는 예외 메시지에 실을 id다(호출부가 받은 id).
    fun assertVisible(post: TablePost, viewerId: UUID?, notFoundId: UUID) {
        if (post.isPrivate && post.userId != viewerId) {
            throw PostNotFoundException(notFoundId)
        }
    }
}
