package com.example.linksphere.domain.member

import com.example.linksphere.domain.auth.SignupRequest
import com.example.linksphere.domain.auth.UpdateAccountRequest
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

        val result = memberService.updateAccount(memberId, request)

        assertEquals("newNick", result.nickname)
        assertEquals("https://example.com/img.png", result.image)
    }

    @Test
    fun `updateAccount throws DuplicateMemberException when nickname already taken`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "old")
        val request = UpdateAccountRequest(nickname = "takenNick")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.existsByNicknameIgnoreCase("takenNick")).thenReturn(true)

        val exception = assertThrows(DuplicateMemberException::class.java) {
            memberService.updateAccount(memberId, request)
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

        memberService.updateAccount(memberId, request)

        verify(memberRepository, never()).existsByNicknameIgnoreCase("sameNick")
    }

    @Test
    fun `updateAccount allows changing nickname case of the same name without duplicate check`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "sameNick")
        val request = UpdateAccountRequest(nickname = "SAMENICK")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.save(member)).thenReturn(member)

        val result = memberService.updateAccount(memberId, request)

        assertEquals("SAMENICK", result.nickname)
        verify(memberRepository, never()).existsByNicknameIgnoreCase("SAMENICK")
    }

    @Test
    fun `signup normalizes email to lowercase and trimmed before checking and saving`() {
        val request = SignupRequest("  Test@Example.com  ", "password", "testuser")
        `when`(memberRepository.existsByEmail("test@example.com")).thenReturn(false)
        `when`(memberRepository.existsByNicknameIgnoreCase("testuser")).thenReturn(false)

        val captor = ArgumentCaptor.forClass(TableMember::class.java)
        `when`(memberRepository.save(captor.capture())).thenAnswer { captor.value }

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
    fun `findByEmail normalizes lookup email so login is case-insensitive`() {
        val member = TableMember(email = "test@example.com", password = "enc", nickname = "testuser")
        `when`(memberRepository.findByEmail("test@example.com")).thenReturn(member)

        val result = memberService.findByEmail("  Test@Example.com  ")

        assertEquals("test@example.com", result.email)
        verify(memberRepository).findByEmail("test@example.com")
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
        `when`(memberRepository.save(captor.capture()))
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
        `when`(memberRepository.save(captor.capture()))
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
        `when`(memberRepository.save(captor.capture()))
            .thenThrow(DataIntegrityViolationException("some other constraint violated"))

        assertThrows(DataIntegrityViolationException::class.java) { memberService.signup(request) }
    }
}
