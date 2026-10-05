package com.example.linksphere.global.exception

import java.util.UUID

/**
 * 답글을 달 부모 댓글이나 수정·삭제할 댓글이 없을 때. 예전에는 범용 NOT_FOUND라 FE가 "글이 삭제됨"과
 * 구분하지 못했다 - 댓글이 사라졌다는 안내를 고를 수 있게 전용 code로 낸다.
 */
class CommentNotFoundException(id: UUID) : RuntimeException("Comment not found with id: $id")
