package com.example.linksphere.domain.member

import com.example.linksphere.global.exception.DuplicateMemberException
import com.example.linksphere.global.exception.DuplicateNicknameException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.dao.DataIntegrityViolationException
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class MemberServiceTest {

    @Mock private lateinit var memberRepository: MemberRepository

    @InjectMocks private lateinit var memberService: MemberService

    // 응답 role은 예전 고정값 "USER"가 아니라 회원 역할이다
    @Test
    fun `getAccount는 회원 역할을 응답 role에 담는다`() {
        val memberId = UUID.randomUUID()
        `when`(memberRepository.findById(memberId))
            .thenReturn(Optional.of(TableMember(id = memberId, email = "demo@example.com", password = "encoded", role = MemberRole.DEMO)))

        val account = memberService.getAccount(memberId)

        assertEquals("DEMO", account.role)
        assertEquals(memberId.toString(), account.id)
        assertEquals("demo@example.com", account.email)
    }

    @Test
    fun `checkNicknameAvailability는 isNicknameAvailable 결과를 응답 DTO로 감싼다`() {
        `when`(memberRepository.existsByNicknameIgnoreCase("taken")).thenReturn(true)

        assertEquals(NicknameAvailabilityResponse(false), memberService.checkNicknameAvailability(null, "taken"))
    }

    @Test
    fun `signup throws DuplicateMemberException when email exists`() {
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(true)

        val exception =
            assertThrows(DuplicateMemberException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Email already exists"))
    }

    @Test
    fun `signup throws DuplicateNicknameException when nickname exists`() {
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase(request.nickname)).thenReturn(true)

        val exception =
            assertThrows(DuplicateNicknameException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Nickname already exists"))
    }

    @Test
    fun `signup throws DuplicateNicknameException when nickname differs only by case`() {
        val request = SignupRequest("test@example.com", "password", "TESTUSER")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase("TESTUSER")).thenReturn(true)

        val exception =
            assertThrows(DuplicateNicknameException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Nickname already exists"))
    }

    @Test
    fun `updateAccount updates nickname and image`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "old")
        val request = UpdateAccountRequest(nickname = "newNick", image = "https://example.com/img.png")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.existsByNicknameIgnoreCase("newNick")).thenReturn(false)
        `when`(memberRepository.save(member)).thenReturn(member)

        val result = memberService.updateAccountAndGet(memberId, request)

        assertEquals("newNick", result.nickname)
        assertEquals("https://example.com/img.png", result.image)
    }

    @Test
    fun `changePassword updates the encoded password`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "oldEncoded", nickname = "tester")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.save(member)).thenReturn(member)

        memberService.changePassword(memberId, "newEncoded")

        assertEquals("newEncoded", member.password)
    }

    @Test
    fun `markEmailVerified sets emailVerified to true`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "tester", emailVerified = false)

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.save(member)).thenReturn(member)

        memberService.markEmailVerified(memberId)

        assertEquals(true, member.emailVerified)
    }

    @Test
    fun `updateAccount throws DuplicateNicknameException when nickname already taken`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "old")
        val request = UpdateAccountRequest(nickname = "takenNick")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.existsByNicknameIgnoreCase("takenNick")).thenReturn(true)

        // 가입(signup)과 같은 code(DUPLICATE_NICKNAME)가 나가야 한다 - 예전에는 DUPLICATE_MEMBER였다
        val exception = assertThrows(DuplicateNicknameException::class.java) {
            memberService.updateAccountAndGet(memberId, request)
        }
        assertTrue(exception.message!!.contains("Nickname already exists"))
    }

    @Test
    fun `updateAccount skips duplicate check when nickname is unchanged`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "sameNick")
        val request = UpdateAccountRequest(nickname = "sameNick")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.save(member)).thenReturn(member)

        memberService.updateAccountAndGet(memberId, request)

        verify(memberRepository, never()).existsByNicknameIgnoreCase("sameNick")
    }

    @Test
    fun `updateAccount allows changing nickname case of the same name without duplicate check`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "sameNick")
        val request = UpdateAccountRequest(nickname = "SAMENICK")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.save(member)).thenReturn(member)

        val result = memberService.updateAccountAndGet(memberId, request)

        assertEquals("SAMENICK", result.nickname)
        verify(memberRepository, never()).existsByNicknameIgnoreCase("SAMENICK")
    }

    @Test
    fun `signup normalizes email to lowercase and trimmed before checking and saving`() {
        val request = SignupRequest("  Test@Example.com  ", "password", "testuser")
        `when`(memberRepository.existsByEmail("test@example.com")).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase("testuser")).thenReturn(false)

        val captor = ArgumentCaptor.forClass(TableMember::class.java)
        `when`(memberRepository.saveAndFlush(captor.capture())).thenAnswer { captor.value }

        val result = memberService.signup(request)

        verify(memberRepository).existsByEmail("test@example.com")
        assertEquals("test@example.com", result.email)
    }

    @Test
    fun `signup throws DuplicateMemberException when email differs only by case`() {
        val request = SignupRequest("TEST@EXAMPLE.COM", "password", "testuser")
        `when`(memberRepository.existsByEmail("test@example.com")).thenReturn(true)

        val exception =
            assertThrows(DuplicateMemberException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Email already exists"))
    }

    @Test
    fun `findCredentialsByEmailOrNull normalizes lookup email so login is case-insensitive`() {
        val member = TableMember(id = UUID.randomUUID(), email = "test@example.com", password = "enc", nickname = "testuser")
        `when`(memberRepository.findByEmail("test@example.com")).thenReturn(member)

        val result = memberService.findCredentialsByEmailOrNull("  Test@Example.com  ")

        assertEquals("test@example.com", result?.email)
        verify(memberRepository).findByEmail("test@example.com")
    }

    // auth는 TableMember를 보지 않고 이 사본만 본다 - 로그인 분기에 쓰는 값이 그대로 실려야 한다
    @Test
    fun `getCredentials는 로그인에 필요한 값을 전용 객체에 담는다`() {
        val memberId = UUID.randomUUID()
        val requestedAt = java.time.Instant.now()
        `when`(memberRepository.findById(memberId))
            .thenReturn(Optional.of(TableMember(id = memberId, email = "demo@example.com", password = "encoded", role = MemberRole.DEMO, emailVerified = true, deletionRequestedAt = requestedAt)))

        assertEquals(
            MemberCredentials(memberId, "demo@example.com", "encoded", MemberRole.DEMO, emailVerified = true, deletionRequestedAt = requestedAt, deletedAt = null),
            memberService.getCredentials(memberId),
        )
    }

    @Test
    fun `findRole은 회원의 역할만 읽고 없으면 null`() {
        val memberId = UUID.randomUUID()
        `when`(memberRepository.findRoleById(memberId)).thenReturn(MemberRole.DEMO)

        assertEquals(MemberRole.DEMO, memberService.findRole(memberId))
        assertEquals(null, memberService.findRole(UUID.randomUUID()))
    }

    @Test
    fun `isNicknameAvailable returns false when another member has the same nickname in different case`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "myNick")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.existsByNicknameIgnoreCase("TAKEN")).thenReturn(true)

        assertEquals(false, memberService.isNicknameAvailable(memberId, "TAKEN"))
    }

    @Test
    fun `isNicknameAvailable returns true for the member's own nickname regardless of case`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "myNick")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))

        assertEquals(true, memberService.isNicknameAvailable(memberId, "MYNICK"))
        verify(memberRepository, never()).existsByNicknameIgnoreCase("MYNICK")
    }

    @Test
    fun `isNicknameAvailable with null id does not look up a member and checks existence only`() {
        `when`(memberRepository.existsByNicknameIgnoreCase("newNick")).thenReturn(false)

        assertEquals(true, memberService.isNicknameAvailable(null, "newNick"))
        verify(memberRepository, never()).findById(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `isNicknameAvailable with null id returns false when nickname is taken`() {
        `when`(memberRepository.existsByNicknameIgnoreCase("taken")).thenReturn(true)

        assertEquals(false, memberService.isNicknameAvailable(null, "taken"))
    }

    @Test
    fun `isEmailAvailable normalizes email before checking existence`() {
        `when`(memberRepository.existsByEmail("test@example.com")).thenReturn(false)

        assertEquals(true, memberService.isEmailAvailable("  Test@Example.com  "))
        verify(memberRepository).existsByEmail("test@example.com")
    }

    @Test
    fun `isEmailAvailable returns false when email already exists`() {
        `when`(memberRepository.existsByEmail("test@example.com")).thenReturn(true)

        assertEquals(false, memberService.isEmailAvailable("test@example.com"))
    }

    @Test
    fun `signup rethrows as DuplicateMemberException when the email unique index is violated at save time`() {
        // 사전 exists 체크는 통과했지만(동시 가입 레이스) save 시점에 DB 유니크 제약이 막은 경우 -
        // 평소 사전 체크 경로와 같은 코드가 나가야 한다
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase(request.nickname)).thenReturn(false)
        val captor = ArgumentCaptor.forClass(TableMember::class.java)
        `when`(memberRepository.saveAndFlush(captor.capture()))
            .thenThrow(
                DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"members_email_lower_key\"",
                ),
            )

        val exception =
            assertThrows(DuplicateMemberException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Email already exists"))
    }

    @Test
    fun `signup rethrows as DuplicateNicknameException when the nickname unique index is violated at save time`() {
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase(request.nickname)).thenReturn(false)
        val captor = ArgumentCaptor.forClass(TableMember::class.java)
        `when`(memberRepository.saveAndFlush(captor.capture()))
            .thenThrow(
                DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"members_nickname_lower_key\"",
                ),
            )

        val exception =
            assertThrows(DuplicateNicknameException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Nickname already exists"))
    }

    @Test
    fun `signup propagates unrecognized DataIntegrityViolationException as-is`() {
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase(request.nickname)).thenReturn(false)
        val captor = ArgumentCaptor.forClass(TableMember::class.java)
        `when`(memberRepository.saveAndFlush(captor.capture()))
            .thenThrow(DataIntegrityViolationException("some other constraint violated"))

        assertThrows(DataIntegrityViolationException::class.java) { memberService.signup(request) }
    }
}
