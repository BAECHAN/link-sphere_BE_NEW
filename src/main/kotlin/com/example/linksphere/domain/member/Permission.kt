package com.example.linksphere.domain.member

/**
 * 역할(MemberRole)이 가질 수 있는 권한. SessionAuthenticationFilter가 요청마다 회원 역할의 권한을 Authentication에 싣고,
 * 컨트롤러의 `@PreAuthorize("hasAuthority('<이름>')")`가 검사한다(docs/BE-ARCHITECTURE.md "역할·권한").
 * 지금은 데모 계정이 바꾸면 안 되는 계정 작업만 권한으로 둔다 - 글·댓글·북마크처럼 막을 역할이 없는 기능은 넣지 않는다.
 */
enum class Permission {
    ACCOUNT_PROFILE_UPDATE, // PATCH /auth/account
    ACCOUNT_PASSWORD_CHANGE, // PATCH /auth/account/password
    ACCOUNT_DELETE, // POST /auth/account/deletion
    SESSION_LOGOUT_ALL, // POST /auth/logout-all
}
