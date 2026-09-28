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
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * 회원 탈퇴 - 2단계, 하드삭제 아님(docs/plans/2026-09-29-account-deletion-grace-period.md).
 *
 * 1단계(requestDeletion)는 신청 즉시 세션만 폐기하고 14일 유예에 들어간다 - 이 기간에
 * 로그인하면(AuthService.login) 신청이 자동 취소된다. 글·댓글 작성자 표시는 TableMember의
 * isWithdrawn 기준으로 유예 중에도 이미 "탈퇴한 사용자"로 보인다(CommentService·
 * PostResponseAssembler 참고) - 본인이 복구하면 자동으로 원래 닉네임이 돌아온다.
 *
 * 2단계(purge)는 AccountPurgeService가 매일 유예가 끝난 회원에 대해 호출한다 - 여기서
 * 실제로 회원 행을 익명화한다(글·댓글은 손대지 않는다 - 익명화된 회원 행을 그대로
 * 참조하므로 작성자 표시는 이미 1단계부터 바뀌어 있다). 남에게 안 보이는 개인 전용
 * 데이터(북마크·좋아요·조회기록·FCM 토큰)는 복구 가능성 때문에 1단계에서는 건드리지
 * 않고 2단계에서만 실제로 지운다 - 다른 도메인 Repository를 직접 참조하는 건 이 레포에서
 * 이미 흔한 패턴이다(예: PostService가 interaction 도메인 Repository를 쓰는 것,
 * ArchitectureRulesTest.kt 참고). FCM 토큰만 예외로 1단계에서 바로 지운다 - 유예 중
 * 푸시가 계속 오면 안 되고, 로그인으로 복구되면 FE가 다시 등록한다.
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

    companion object {
        val GRACE_PERIOD: Duration = Duration.ofDays(14)
    }

    @Transactional
    fun requestDeletion(userId: String, rawPassword: String) {
        val id = UUID.fromString(userId)
        val member = memberRepository.findById(id).orElseThrow { IllegalArgumentException("Member not found with id: $userId") }

        if (!passwordEncoder.matches(rawPassword, member.password)) {
            throw InvalidCredentialsException("Password is incorrect")
        }

        // 이미 유예 중이면 타임스탬프를 유지한다 - 다시 신청한다고 유예가 14일 더 늘어나지
        // 않는다(사실 세션이 이미 폐기돼 있어 재호출 자체가 거의 불가능하지만, 방어적으로 둔다).
        if (member.deletionRequestedAt == null) {
            member.deletionRequestedAt = Instant.now()
            memberRepository.save(member)
        }

        memberSessionService.revokeAllForMember(id)
        fcmTokenRepository.deleteByUserId(id)
    }

    /**
     * cutoff 이전에 유예가 시작된 경우에만 익명화를 확정한다(claimForPurge의 조건부
     * UPDATE). 0행이면 이미 로그인으로 복구됐거나 애초에 유예 중이 아니었던 것이라
     * 아무것도 하지 않고 false를 반환한다 - AccountPurgeService가 건너뛴 것으로 센다.
     */
    @Transactional
    fun purge(id: UUID, cutoff: Instant): Boolean {
        val claimed = memberRepository.claimForPurge(id, cutoff, Instant.now())
        if (claimed == 0) return false

        val member = memberRepository.findById(id).orElseThrow { IllegalStateException("Member not found with id: $id (claimed for purge)") }

        // 이메일: RFC 2606이 예약한 .invalid TLD - 실제로 존재할 수 없는 도메인이라 재사용
        // 걱정 없이 유니크 제약을 만족시킨다. 비밀번호: 무작위 문자열을 인코딩한 값이라
        // 어떤 입력으로도 다시 매칭될 수 없다(SecureToken.generate()는 세션 토큰과 같은
        // 32바이트 난수 - 여기선 세션이 아니라 "matches() 불가능한 값"이 필요할 뿐이다).
        member.email = "deleted-$id@deleted.invalid"
        member.password = passwordEncoder.encode(SecureToken.generate())
        member.nickname = null
        member.image = null
        // deletedAt은 claimForPurge가 이미 세팅했다.
        memberRepository.save(member)

        // 유예 중 새 세션이 생겼을 가능성에 대한 방어(경합 R4) - 정상 흐름에선 1단계에서
        // 이미 전부 폐기돼 있다.
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
        return true
    }
}
