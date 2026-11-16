package com.example.linksphere.domain.member

import java.time.Instant
import java.util.UUID

/**
 * 로그인·비밀번호 변경·비밀번호 찾기가 회원을 확인하는 데 필요한 값만 담는다. auth가 TableMember를 직접 보지 않게 하는
 * 전용 객체다 - 읽은 시점의 사본이라 값을 바꿔도 저장되지 않는다(바꾸려면 MemberService의 메서드를 부른다).
 */
data class MemberCredentials(
    val id: UUID,
    val email: String,
    val encodedPassword: String,
    val role: MemberRole,
    val emailVerified: Boolean,
    val deletionRequestedAt: Instant?,
    val deletedAt: Instant?,
) {
    companion object {
        fun from(member: TableMember): MemberCredentials = MemberCredentials(
            id = member.id!!,
            email = member.email,
            encodedPassword = member.password,
            role = member.role,
            emailVerified = member.emailVerified,
            deletionRequestedAt = member.deletionRequestedAt,
            deletedAt = member.deletedAt,
        )
    }
}
