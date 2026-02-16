package com.example.linksphere.domain.member

import com.example.linksphere.domain.auth.SignupRequest
import com.example.linksphere.global.exception.DuplicateMemberException
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
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
    fun `signup throws DuplicateMemberException when name exists`() {
        val request = SignupRequest("test@example.com", "password", "testuser")
        `when`(memberRepository.existsByEmail(request.email)).thenReturn(false)
        `when`(memberRepository.existsByName(request.name!!)).thenReturn(true)

        val exception =
                assertThrows(DuplicateMemberException::class.java) { memberService.signup(request) }
        assertTrue(exception.message!!.contains("Name already exists"))
    }
}
