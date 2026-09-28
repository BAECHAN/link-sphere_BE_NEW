package com.example.linksphere.domain.auth

import com.example.linksphere.domain.interaction.BookmarkFolderItemRepository
import com.example.linksphere.domain.interaction.BookmarkFolderRepository
import com.example.linksphere.domain.interaction.BookmarkRepository
import com.example.linksphere.domain.interaction.CommentReactionRepository
import com.example.linksphere.domain.interaction.PostReactionRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.domain.member.TableMember
import com.example.linksphere.domain.post.PostViewRepository
import com.example.linksphere.global.exception.InvalidCredentialsException
import com.example.linksphere.infra.fcm.FcmTokenRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.Optional
import java.util.UUID

class AccountDeletionServiceTest {

    private lateinit var memberRepository: MemberRepository
    private lateinit var memberSessionService: MemberSessionService
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var bookmarkRepository: BookmarkRepository
    private lateinit var bookmarkFolderRepository: BookmarkFolderRepository
    private lateinit var bookmarkFolderItemRepository: BookmarkFolderItemRepository
    private lateinit var postReactionRepository: PostReactionRepository
    private lateinit var commentReactionRepository: CommentReactionRepository
    private lateinit var postViewRepository: PostViewRepository
    private lateinit var fcmTokenRepository: FcmTokenRepository
    private lateinit var service: AccountDeletionService

    @BeforeEach
    fun setUp() {
        memberRepository = mock(MemberRepository::class.java)
        memberSessionService = mock(MemberSessionService::class.java)
        passwordEncoder = mock(PasswordEncoder::class.java)
        bookmarkRepository = mock(BookmarkRepository::class.java)
        bookmarkFolderRepository = mock(BookmarkFolderRepository::class.java)
        bookmarkFolderItemRepository = mock(BookmarkFolderItemRepository::class.java)
        postReactionRepository = mock(PostReactionRepository::class.java)
        commentReactionRepository = mock(CommentReactionRepository::class.java)
        postViewRepository = mock(PostViewRepository::class.java)
        fcmTokenRepository = mock(FcmTokenRepository::class.java)
        service =
            AccountDeletionService(
                memberRepository,
                memberSessionService,
                passwordEncoder,
                bookmarkRepository,
                bookmarkFolderRepository,
                bookmarkFolderItemRepository,
                postReactionRepository,
                commentReactionRepository,
                postViewRepository,
                fcmTokenRepository,
            )
    }

    @Test
    fun `비밀번호가 틀리면 익명화·세션폐기·개인데이터삭제 전부 건드리지 않는다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("wrong", "encoded")).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            service.deleteAccount(memberId.toString(), "wrong")
        }

        verify(memberRepository, never()).save(org.mockito.ArgumentMatchers.any())
        verifyNoInteractions(memberSessionService, bookmarkRepository, bookmarkFolderRepository, bookmarkFolderItemRepository, postReactionRepository, commentReactionRepository, postViewRepository, fcmTokenRepository)
    }

    @Test
    fun `성공하면 회원 행을 익명화하고 세션을 전부 폐기하고 개인 데이터를 전부 지운다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester", image = "https://example.com/a.png")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("correct", "encoded")).thenReturn(true)
        `when`(passwordEncoder.encode(org.mockito.ArgumentMatchers.anyString())).thenReturn("unmatchable")

        service.deleteAccount(memberId.toString(), "correct")

        assertEquals("deleted-$memberId@deleted.invalid", member.email)
        assertEquals("unmatchable", member.password)
        assertNull(member.nickname)
        assertNull(member.image)
        assertNotEquals(null, member.deletedAt)
        verify(memberRepository).save(member)

        verify(memberSessionService).revokeAllForMember(memberId)
        verify(bookmarkFolderItemRepository).deleteByUserId(memberId)
        verify(bookmarkFolderRepository).deleteByUserId(memberId)
        verify(bookmarkRepository).deleteByUserId(memberId)
        verify(postReactionRepository).deleteByUserId(memberId)
        verify(commentReactionRepository).deleteByUserId(memberId)
        verify(postViewRepository).deleteByUserId(memberId)
        verify(fcmTokenRepository).deleteByUserId(memberId)
    }
}
