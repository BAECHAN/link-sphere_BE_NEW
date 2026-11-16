package com.example.linksphere.domain.post

import java.util.UUID

/**
 * 글이 지워질 때 post가 내는 이벤트(docs/plans/2026-11-16-be-structure-stage2.md PR-8). member의 MemberLifecycleEvents.kt와
 * 같은 방식이다 - post는 그 글에 딸린 다른 패키지의 데이터를 모르고 "곧 지운다"고만 알린다. 듣는 쪽은 `@EventListener`라
 * 발행한 스레드·트랜잭션 안에서 바로 실행되고, 실패하면 예외가 올라와 글 삭제가 되돌려진다.
 */

/**
 * 글 행을 지우기 **직전**에 낸다. 댓글 행·좋아요·북마크는 DB의 ON DELETE CASCADE가 지우므로 듣는 쪽이 필요 없고, DB가
 * 지워 주지 않는 것(댓글 본문에 든 스토리지 이미지)만 정리한다. 삭제 SQL이 DB에 나간 뒤에는 댓글 행이 이미 없어 이미지 URL을
 * 읽을 수 없다 - JPA가 삭제를 커밋 직전까지 미루는 데 기대지 않도록 delete를 부르기 전에 낸다.
 */
data class PostDeletingEvent(val postId: UUID)
