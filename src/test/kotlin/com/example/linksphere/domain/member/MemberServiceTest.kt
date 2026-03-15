package com.example.linksphere.domain.member

import com.example.linksphere.domain.auth.SignupRequest
import com.example.linksphere.domain.auth.UpdateAccountRequest
import com.example.linksphere.global.exception.DuplicateMemberException
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

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
    fun `signup throws DuplicateMemberException when nickname exists`() {
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByNickname(request.nickname!!)).thenReturn(true)

        val exception =
                assertThrows(DuplicateMemberException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Nickname already exists"))
    }

    @Test
    fun `updateAccount updates nickname and image`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "enc", nickname = "old")
        val request = UpdateAccountRequest(nickname = "newNick", image = "https://example.com/img.png")

        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(memberRepository.existsByNickname("newNick")).thenReturn(false)
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
        `when`(memberRepository.existsByNickname("takenNick")).thenReturn(true)

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

        verify(memberRepository, never()).existsByNickname("sameNick")
    }
}
