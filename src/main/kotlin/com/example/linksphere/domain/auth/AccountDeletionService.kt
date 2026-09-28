package com.example.linksphere.domain.auth

import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.post.PostViewRepository
import com.example.linksphere.global.common.SecureToken
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.infra.fcm.FcmTokenRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * 회원 탈퇴 - 하드삭제가 아니라 계정 행 익명화(docs/plans/2026-09-28-auth-hardening.md
 * "확정된 결정들" 참고). 글·댓글은 손대지 않는다 - 익명화된 회원 행을 그대로 참조하므로
 * 작성자 표시가 자동으로 "탈퇴한 사용자"가 된다(TableMember.kt 주석, CommentService의
 * 닉네임 null 폴백 참고). 댓글은 기존 "답글 있으면 흔적만 남기고 익명화" 톰스톤 로직을
 * 그대로 재사용한다(CommentService.deleteComment와는 별개 - 여긴 회원 행만 건드린다).
 *
 * 남에게 안 보이는 개인 전용 데이터(북마크·좋아요·조회기록·FCM 토큰)만 실제로 지운다 -
 * 다른 도메인 Repository를 직접 참조하는 건 이 레포에서 이미 흔한 패턴이다(예: PostService가
 * interaction 도메인 Repository를 쓰는 것, ArchitectureRulesTest.kt 참고).
 */
@Service
class AccountDeletionService(
    private val memberRepository: MemberRepository,
    private val memberSessionService: MemberSessionService,
    private val passwordEncoder: PasswordEncoder,
    private val bookmarkRepository: BookmarkRepository,
    private val bookmarkFolderRepository: BookmarkFolderRepository,
    private val bookmarkFolderItemRepository: BookmarkFolderItemRepository,
    private val postReactionRepository: PostReactionRepository,
    private val commentReactionRepository: CommentReactionRepository,
    private val postViewRepository: PostViewRepository,
    private val fcmTokenRepository: FcmTokenRepository,
) {

    @Transactional
    fun deleteAccount(userId: String, rawPassword: String) {
        val id = UUID.fromString(userId)
        val member = memberRepository.findById(id).orElseThrow { IllegalArgumentException("Member not found with id: $userId") }

        if (!passwordEncoder.matches(rawPassword, member.password)) {
            throw InvalidCredentialsException("Password is incorrect")
        }

        // 이메일: RFC 2606이 예약한 .invalid TLD - 실제로 존재할 수 없는 도메인이라 재사용
        // 걱정 없이 유니크 제약을 만족시킨다. 비밀번호: 무작위 문자열을 인코딩한 값이라
        // 어떤 입력으로도 다시 매칭될 수 없다(SecureToken.generate()는 세션 토큰과 같은
        // 32바이트 난수 - 여기선 세션이 아니라 "matches() 불가능한 값"이 필요할 뿐이다).
        member.email = "deleted-$id@deleted.invalid"
        member.password = passwordEncoder.encode(SecureToken.generate())
        member.nickname = null
        member.image = null
        member.deletedAt = Instant.now()
        memberRepository.save(member)

        memberSessionService.revokeAllForMember(id)

        // 폴더 아이템 → 폴더 순으로 지운다(BookmarkFolderService.deleteFolder와 같은 순서 -
        // 폴더가 아이템보다 먼저 사라지면 안 됨).
        bookmarkFolderItemRepository.deleteByUserId(id)
        bookmarkFolderRepository.deleteByUserId(id)
        bookmarkRepository.deleteByUserId(id)
        postReactionRepository.deleteByUserId(id)
        commentReactionRepository.deleteByUserId(id)
        postViewRepository.deleteByUserId(id)
        fcmTokenRepository.deleteByUserId(id)
    }
}
