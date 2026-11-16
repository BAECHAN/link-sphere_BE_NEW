package com.example.linksphere.domain.member

import java.util.UUID

/**
 * 글·댓글의 작성자로 남에게 보이는 회원 정보다. 다른 패키지(post·comment)가 TableMember를 직접 보지 않게 하는 전용 객체다.
 *
 * 탈퇴 신청 중이거나 익명화가 끝난 회원은 nickname·image가 null이다(TableMember.isWithdrawn) - "남에게는 탈퇴한 것처럼
 * 보인다"는 규칙을 member 안에서 적용해 내보낸다. null일 때 어떤 문구를 보일지는 받는 쪽이 정한다.
 */
data class MemberProfile(val id: UUID, val nickname: String?, val image: String?) {
    companion object {
        fun from(member: TableMember): MemberProfile = MemberProfile(id = member.id!!, nickname = member.publicNickname, image = member.publicImage)
    }
}
