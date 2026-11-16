package com.example.linksphere.domain.member

import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.global.exception.DuplicateNicknameException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
@Transactional(readOnly = true)
class MemberService(private val memberRepository: MemberRepository) {

    // 다른 패키지에 TableMember를 내주지 않는다 - 공개 메서드는 id, 응답 DTO, 전용 객체(MemberCredentials·MemberProfile)를 돌려준다.
    @Transactional
    fun signup(request: SignupRequest): AccountResponse {
        val normalizedEmail = EmailNormalizer.normalize(request.email)

        // 예외 메시지에 제출값을 반사하지 않는다 - FE는 이 메시지를 표시하지 않고 code로만
        // 분기하므로 불필요하게 입력 이메일을 그대로 돌려줄 이유가 없다
        if (memberRepository.existsByEmail(normalizedEmail)) {
            throw DuplicateMemberException("Email already exists")
        }

        // 이메일과는 다른 예외/코드로 구분한다 - 둘 다 DUPLICATE_MEMBER 하나였을 때 FE가 닉네임
        // 중복인데도 "이메일이 이미 가입돼 있어요"라고 잘못 안내하는 문제가 있었다.
        // 대소문자 무시 비교 - 과거 대소문자 구분 시절 "Tester02"/"tester02"처럼 같은 이름으로
        // 보이는 계정이 둘 다 생긴 적이 있어(2026-08-11 실DB 확인) 표시 혼동을 막는다
        if (memberRepository.existsByNicknameIgnoreCase(request.nickname)) {
            throw DuplicateNicknameException(request.nickname)
        }

        // Password is already encrypted by AuthService before calling this, or we can assume the
        // caller handles it.
        // The previous step in AuthService passed `request.copy(password = encoded)`.
        // So here we just use it.

        val newMember =
            TableMember(
                email = normalizedEmail,
                password = request.password,
                nickname = request.nickname,
            )

        // 위 두 exists 체크와 이 save 사이에는 여전히 레이스 윈도우가 있다(동시 가입 요청이
        // 둘 다 체크를 통과한 뒤 하나만 insert에 성공). Step 1·2에서 새로 건 유니크 인덱스
        // (members_email_lower_key/members_nickname_lower_key)가 그 경우 DB 레벨에서 막아
        // 주지만, 그대로 두면 DataIntegrityViolationException 전역 핸들러가 어느 필드가
        // 충돌했는지 모른 채 DUPLICATE_RESOURCE(한글 메시지)로 뭉뚱그려 응답해 위의 사전
        // 체크 경로와 코드가 갈라진다. 어느 인덱스가 깨졌는지 이름으로 구분해 같은 예외로
        // 다시 던져 FE가 항상 같은 code를 받게 한다.
        //
        // save()가 아니라 saveAndFlush()여야 한다 - id가 @GeneratedValue UUID라 save()는 persist만 하고 INSERT를
        // 커밋 때로 미뤄, 제약 위반이 이 try 밖(커밋)에서 나 DUPLICATE_RESOURCE로 나갔다(MemberServiceIntegrationTest,
        // PostAIService.processAiJob의 saveAndFlush와 같은 이유).
        try {
            return AccountResponse.from(memberRepository.saveAndFlush(newMember))
        } catch (e: DataIntegrityViolationException) {
            val cause = e.mostSpecificCause.message ?: ""
            when {
                cause.contains("members_email_lower_key") -> throw DuplicateMemberException("Email already exists")
                cause.contains("members_nickname_lower_key") -> throw DuplicateNicknameException(request.nickname)
                else -> throw e
            }
        }
    }

    // 없으면 예외 대신 null - 호출부(AuthService)가 트랜잭션 안에서 "회원 없음"을 정상 흐름으로
    // 다룬다. 예외로 알리면 이 클래스의 @Transactional 경계를 지나며 호출부 트랜잭션이
    // rollback-only로 표시돼, 호출부가 잡고 정상 종료해도 커밋 시점에
    // UnexpectedRollbackException(500)이 난다(docs/TRAFFIC-MANAGEMENT.md 시행착오 참고).
    fun findCredentialsByEmailOrNull(email: String): MemberCredentials? = memberRepository.findByEmail(EmailNormalizer.normalize(email))?.let { MemberCredentials.from(it) }

    fun getCredentials(id: UUID): MemberCredentials = MemberCredentials.from(findById(id))

    // SessionAuthenticationFilter가 로그인한 요청마다 부른다(MemberSessionService.checkAccessToken). 회원이 없으면 null.
    fun findRole(id: UUID): MemberRole? = memberRepository.findRoleById(id)

    // 아래 여덟은 post·comment·feed·upload가 회원을 읽는 길이다 - TableMember 대신 id와 전용 객체(MemberProfile)만 내준다.

    // 글·댓글 목록의 작성자를 한 번에 읽는다. 없는 id는 결과에 빠진다. 빈 목록이면 DB를 보지 않는다.
    fun findProfiles(ids: Collection<UUID>): Map<UUID, MemberProfile> {
        val distinctIds = ids.toSet()

        if (distinctIds.isEmpty()) {
            return emptyMap()
        }

        return memberRepository.findAllById(distinctIds).associate { it.id!! to MemberProfile.from(it) }
    }

    // 단건은 findById로 읽는다 - 같은 트랜잭션에서 requireEmailVerified가 이미 읽은 회원이면 쿼리가 다시 나가지 않는다.
    fun getProfile(id: UUID): MemberProfile = MemberProfile.from(findById(id))

    // 글쓰기·댓글쓰기·링크 미리보기 전에 부른다. action은 메시지에만 쓴다(EmailVerificationGate).
    fun requireEmailVerified(id: UUID, action: String) = EmailVerificationGate.require(findById(id), action)

    // 닉네임에 이 글자가 든 회원의 id. 대소문자를 가리지 않고, `%`·`_`는 예전 그대로 이스케이프하지 않는다(와일드카드로 동작).
    fun findIdsByNicknameContaining(nickname: String): Set<UUID> = memberRepository.findIdsByNicknamePattern("%${nickname.lowercase()}%").toSet()

    fun findBotIds(): Set<UUID> = memberRepository.findBotIds().toSet()

    // RSS 피드 수집이 글을 올릴 봇 계정. 봇이 여럿이면 가장 먼저 만든 하나다(MemberRepository.findFirstByIsBotTrue).
    fun findFirstBotId(): UUID? = memberRepository.findFirstByIsBotTrue()?.id

    // 댓글 이미지를 지우기 직전에 이 URL을 누군가 아바타로 쓰는지 확인한다(CommentService 삭제 경로).
    fun isImageInUse(url: String): Boolean = memberRepository.existsByImage(url)

    // 고아 이미지 정리(OrphanImageGcService)가 참조 집합을 만들 때 쓴다.
    fun findAllImageUrls(): List<String> = memberRepository.findAllImageUrls()

    private fun findById(id: UUID): TableMember = memberRepository.findById(id).orElseThrow { IllegalStateException("Member not found with id: $id") }

    private fun updateAccount(id: UUID, request: UpdateAccountRequest): TableMember {
        val member = findById(id)
        request.nickname?.let {
            if (!it.equals(member.nickname, ignoreCase = true) && memberRepository.existsByNicknameIgnoreCase(it)) {
                throw DuplicateNicknameException(it)
            }
            member.nickname = it
        }
        request.image?.let { member.image = it }
        member.updatedAt = LocalDateTime.now()
        return memberRepository.save(member)
    }

    // 아래 셋은 MemberController 전용이다 - 컨트롤러는 엔티티를 모르므로(LayerSkeletonRulesTest) 응답 DTO로 바꿔 돌려준다.
    fun getAccount(id: UUID): AccountResponse = AccountResponse.from(findById(id))

    @Transactional
    fun updateAccountAndGet(id: UUID, request: UpdateAccountRequest): AccountResponse = AccountResponse.from(updateAccount(id, request))

    // userId가 없으면(가입 화면, 비로그인 조회) 본인 제외 없이 순수 존재 여부만 확인한다
    fun checkNicknameAvailability(id: UUID?, nickname: String): NicknameAvailabilityResponse = NicknameAvailabilityResponse(isNicknameAvailable(id, nickname))

    // 새 비밀번호는 AuthService가 이미 인코딩해서 넘긴다 - updateAccount와 같은 위치에 두는
    // 이유는 "회원 데이터 변경은 MemberService를 거친다"는 규칙을 password에도 그대로
    // 적용하기 위함(AuthService는 TableMember 필드를 직접 건드리지 않는다). 비밀번호 찾기(PasswordResetService)도 이것을 쓴다.
    @Transactional
    fun changePassword(id: UUID, encodedPassword: String) {
        val member = findById(id)
        member.password = encodedPassword
        member.updatedAt = LocalDateTime.now()
        memberRepository.save(member)
    }

    // 이메일 인증 확인(AuthService.confirmEmailVerification)이 토큰을 소비한 뒤 호출한다.
    @Transactional
    fun markEmailVerified(id: UUID) {
        val member = findById(id)
        member.emailVerified = true
        member.updatedAt = LocalDateTime.now()
        memberRepository.save(member)
    }

    // 로그인 성공 시(AuthService.login) 탈퇴 유예 중인 회원의 신청을 취소한다. AuthService가
    // TableMember 필드를 직접 건드리지 않는다는 규칙(changePassword와 같은 이유)을 지킨다.
    // false면 애초에 유예 중이 아니었거나, 퍼지가 먼저 그 회원을 가져간 것이다 - 어느
    // 쪽인지는 호출부가 로그인 때 읽은 MemberCredentials.deletionRequestedAt으로 구분한다.
    @Transactional
    fun cancelPendingDeletion(id: UUID): Boolean = memberRepository.cancelDeletionRequest(id) == 1

    // id가 있으면(로그인 사용자의 마이페이지 수정) 본인 현재 닉네임은 중복으로 치지 않는다.
    // id가 없으면(가입 화면, 비로그인) 무조건 존재 여부만 본다.
    fun isNicknameAvailable(id: UUID?, nickname: String): Boolean {
        val currentNickname = id?.let { findById(it).nickname }
        return nickname.equals(currentNickname, ignoreCase = true) || !memberRepository.existsByNicknameIgnoreCase(nickname)
    }

    // 가입 화면 실시간 중복확인용 - Step 1의 정규화를 반드시 거쳐야 대소문자만 바꿔 검사를
    // 우회할 수 없다
    fun isEmailAvailable(email: String): Boolean = !memberRepository.existsByEmail(EmailNormalizer.normalize(email))
}
