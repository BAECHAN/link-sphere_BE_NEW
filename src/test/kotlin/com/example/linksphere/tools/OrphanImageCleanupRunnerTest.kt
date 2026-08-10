package com.example.linksphere.tools

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.common.SupabaseStorageService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

// Kotlin에서 ArgumentMatchers.any()를 non-null 파라미터(Collection<String>) 자리에 그대로 쓰면
// 반환값이 실제로는 null이라 호출부에서 NullPointerException이 나고, Mockito의 매처 스택까지
// 어긋나 이 클래스의 다른 테스트까지 연쇄로 깨진다. unchecked cast로 우회한다.
private fun <T> anyCollection(): T {
    ArgumentMatchers.any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

private fun <T> captureValue(captor: ArgumentCaptor<T>): T {
    captor.capture()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

@ExtendWith(MockitoExtension::class)
class OrphanImageCleanupRunnerTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var supabaseStorageService: SupabaseStorageService

    @InjectMocks private lateinit var runner: OrphanImageCleanupRunner

    private val referencedUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/referenced.png"
    private val avatarUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/avatar.png"
    private val orphanUrl = "https://xyz.supabase.co/storage/v1/object/public/comments/orphan.png"

    private fun stubReferencedAndBucket() {
        `when`(commentRepository.findAllContent()).thenReturn(listOf("댓글 내용\n\n$referencedUrl"))
        `when`(memberRepository.findAllImageUrls()).thenReturn(listOf(avatarUrl))
        // isManagedUrl은 참조된 콘텐츠(comment/member)에서 뽑아낸 URL에만 호출된다 - 버킷 목록의
        // orphanUrl은 참조 목록에 아예 등장하지 않으므로 여기서 검사 대상이 되지 않는다.
        listOf(referencedUrl, avatarUrl).forEach {
            `when`(supabaseStorageService.isManagedUrl(it)).thenReturn(true)
        }
        `when`(supabaseStorageService.listAllObjectUrls()).thenReturn(listOf(referencedUrl, avatarUrl, orphanUrl))
    }

    @Test
    fun `dry-run by default does not delete anything`() {
        stubReferencedAndBucket()

        runner.run(emptyArray())

        verify(supabaseStorageService, never()).deleteObjectsByPublicUrls(anyCollection())
    }

    @Test
    fun `--delete removes only the object not referenced by any comment or member`() {
        stubReferencedAndBucket()

        runner.run(arrayOf("--delete"))

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<String>>
        verify(supabaseStorageService).deleteObjectsByPublicUrls(captureValue(captor))
        assertEquals(setOf(orphanUrl), captor.value.toSet())
    }

    @Test
    fun `--delete does not call delete when there are no orphans`() {
        `when`(commentRepository.findAllContent()).thenReturn(listOf("댓글 내용\n\n$referencedUrl"))
        `when`(memberRepository.findAllImageUrls()).thenReturn(emptyList())
        `when`(supabaseStorageService.isManagedUrl(referencedUrl)).thenReturn(true)
        `when`(supabaseStorageService.listAllObjectUrls()).thenReturn(listOf(referencedUrl))

        runner.run(arrayOf("--delete"))

        verify(supabaseStorageService, never()).deleteObjectsByPublicUrls(anyCollection())
    }
}
