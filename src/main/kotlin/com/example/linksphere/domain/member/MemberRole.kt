package com.example.linksphere.domain.member

/**
 * members.role 값. 역할마다 가진 권한(permissions)과, 비밀번호를 공개한 공유 계정인지(sharedCredentials)를 정한다
 * (docs/BE-ARCHITECTURE.md "역할·권한"). enum에 없는 값이 DB에 있으면 그 회원을 불러올 때 변환에서 실패하므로, 역할을
 * 더할 때는 여기 한 줄과 운영 DB의 CHECK 제약(members_role_check, 만들어 뒀다면)을 함께 넓힌다.
 */
enum class MemberRole(val permissions: Set<Permission>, val sharedCredentials: Boolean) {
    USER(
        setOf(
            Permission.ACCOUNT_PROFILE_UPDATE,
            Permission.ACCOUNT_PASSWORD_CHANGE,
            Permission.ACCOUNT_DELETE,
            Permission.SESSION_LOGOUT_ALL,
        ),
        sharedCredentials = false,
    ),

    // README에 비밀번호를 공개하는 포트폴리오 데모 계정. 글·댓글은 쓸 수 있지만 계정 설정은 바꾸지 못한다. 비밀번호가
    // 공개라 로그인 실패로 이메일을 잠그지 않고(AuthService.login) 재설정 메일도 보내지 않는다(PasswordResetService).
    DEMO(emptySet(), sharedCredentials = true),
}
