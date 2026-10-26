package com.example.linksphere.domain.member

import com.example.linksphere.global.exception.EmailNotVerifiedException

/**
 * 이메일 인증을 마치지 않은 회원의 글쓰기·댓글쓰기·링크 미리보기를 막는다(403 EMAIL_NOT_VERIFIED). 읽기·좋아요·북마크는
 * 막지 않는다(docs/plans/2026-09-28-auth-hardening.md "확정된 결정들"). EmailNotVerifiedException은 여기서만 만든다 —
 * LayerSkeletonRulesTest가 막는다.
 */
object EmailVerificationGate {

    // action은 메시지에만 쓴다("create a post" → "Email verification required to create a post")
    fun require(member: TableMember, action: String) {
        if (!member.emailVerified) {
            throw EmailNotVerifiedException("Email verification required to $action")
        }
    }
}
