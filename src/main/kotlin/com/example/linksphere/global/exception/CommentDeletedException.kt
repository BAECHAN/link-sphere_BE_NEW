package com.example.linksphere.global.exception

import java.util.UUID

/**
 * 이미 삭제(톰스톤)된 댓글을 수정하려 할 때. 예전에는 IllegalStateException이라 500으로 나갔다 -
 * 서버 장애가 아니라 다른 화면에서 먼저 지운 경우라 409로 알린다.
 */
class CommentDeletedException(id: UUID) : RuntimeException("Cannot update a deleted comment: $id")
