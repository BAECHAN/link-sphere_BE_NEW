package com.example.linksphere.domain.member

import java.util.UUID

/**
 * 회원의 탈퇴 단계가 바뀔 때 member가 내는 이벤트(docs/plans/2026-11-16-be-structure-stage2.md PR-6).
 *
 * member는 세션·알림 토큰·북마크처럼 다른 패키지가 가진 회원 데이터를 모른다. "일어났다"고만 알리고, 그 데이터를 가진
 * 패키지가 듣고 자기 것을 정리한다(`*MemberEventListener`). 듣는 쪽은 `@EventListener`라 발행한 스레드·트랜잭션 안에서
 * 바로 실행된다 - 하나가 실패하면 예외가 발행한 쪽으로 올라와 그 회원의 처리 전체가 되돌려진다(AccountDeletionService는
 * 예외를 잡지 않는다). 커밋 뒤에 실행되는 @TransactionalEventListener(AFTER_COMMIT)와 다르다.
 */

/** 탈퇴 신청(유예 시작). 로그인을 끊어야 하는 것만 지금 정리한다 - auth가 세션을 폐기하고 fcm이 알림 토큰을 지운다. */
data class MemberDeletionRequestedEvent(val memberId: UUID)

/** 유예가 끝나 회원 행을 익명화했다. 그 회원의 개인 전용 데이터를 가진 패키지가 모두 지운다. */
data class MemberPurgedEvent(val memberId: UUID)
