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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.security.crypto.password.PasswordEncoder
import java.time.Instant
import java.time.temporal.ChronoUnit
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
    fun `requestDeletion은 비밀번호가 틀리면 아무것도 건드리지 않는다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("wrong", "encoded")).thenReturn(false)

        assertThrows(InvalidCredentialsException::class.java) {
            service.requestDeletion(memberId.toString(), "wrong")
        }

        verify(memberRepository, never()).save(org.mockito.ArgumentMatchers.any())
        verifyNoInteractions(memberSessionService, fcmTokenRepository, bookmarkRepository, bookmarkFolderRepository, bookmarkFolderItemRepository, postReactionRepository, commentReactionRepository, postViewRepository)
    }

    @Test
    fun `requestDeletion은 성공하면 유예 타임스탬프를 채우고 세션·FCM토큰만 지운다`() {
        val memberId = UUID.randomUUID()
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester", image = "https://example.com/a.png")
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("correct", "encoded")).thenReturn(true)

        service.requestDeletion(memberId.toString(), "correct")

        assertEquals("test@example.com", member.email)
        assertEquals("tester", member.nickname)
        assertNotNull(member.deletionRequestedAt)
        assertNull(member.deletedAt)
        verify(memberRepository).save(member)
        verify(memberSessionService).revokeAllForMember(memberId)
        verify(fcmTokenRepository).deleteByUserId(memberId)
        verifyNoInteractions(bookmarkRepository, bookmarkFolderRepository, bookmarkFolderItemRepository, postReactionRepository, commentReactionRepository, postViewRepository)
    }

    @Test
    fun `requestDeletion은 이미 유예 중이면 타임스탬프를 유지한다`() {
        val memberId = UUID.randomUUID()
        val original = Instant.now().minus(3, ChronoUnit.DAYS)
        val member = TableMember(id = memberId, email = "test@example.com", password = "encoded", nickname = "tester", deletionRequestedAt = original)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.matches("correct", "encoded")).thenReturn(true)

        service.requestDeletion(memberId.toString(), "correct")

        assertEquals(original, member.deletionRequestedAt)
        verify(memberRepository, never()).save(org.mockito.ArgumentMatchers.any())
        verify(memberSessionService).revokeAllForMember(memberId)
        verify(fcmTokenRepository).deleteByUserId(memberId)
    }

    @Test
    fun `purge는 claim이 0행이면 아무것도 하지 않고 false를 반환한다`() {
        val memberId = UUID.randomUUID()
        val cutoff = Instant.now().minus(14, ChronoUnit.DAYS)
        `when`(memberRepository.claimForPurge(org.mockito.ArgumentMatchers.eq(memberId), org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any()))
            .thenReturn(0)

        val result = service.purge(memberId, cutoff)

        assertFalse(result)
        verify(memberRepository, never()).findById(memberId)
        verifyNoInteractions(memberSessionService, fcmTokenRepository, bookmarkRepository, bookmarkFolderRepository, bookmarkFolderItemRepository, postReactionRepository, commentReactionRepository, postViewRepository)
    }

    @Test
    fun `purge는 claim이 성공하면 회원 행을 익명화하고 세션을 전부 폐기하고 개인 데이터를 전부 지운다`() {
        val memberId = UUID.randomUUID()
        val cutoff = Instant.now().minus(14, ChronoUnit.DAYS)
        val member =
            TableMember(
                id = memberId,
                email = "test@example.com",
                password = "encoded",
                nickname = "tester",
                image = "https://example.com/a.png",
                deletionRequestedAt = Instant.now().minus(15, ChronoUnit.DAYS),
                deletedAt = Instant.now(),
            )
        `when`(memberRepository.claimForPurge(org.mockito.ArgumentMatchers.eq(memberId), org.mockito.ArgumentMatchers.eq(cutoff), org.mockito.ArgumentMatchers.any()))
            .thenReturn(1)
        `when`(memberRepository.findById(memberId)).thenReturn(Optional.of(member))
        `when`(passwordEncoder.encode(anyString())).thenReturn("unmatchable")

        val result = service.purge(memberId, cutoff)

        assertTrue(result)
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
