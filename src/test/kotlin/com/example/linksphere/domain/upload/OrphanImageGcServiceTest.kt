package com.example.linksphere.domain.upload

import com.example.linksphere.domain.comment.CommentRepository
import com.example.linksphere.domain.member.MemberRepository
import com.example.linksphere.global.common.SupabaseStorageService
import com.example.linksphere.global.common.SupabaseStorageService.StoredObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension
import java.time.Duration
import java.time.Instant

// OrphanImageCleanupRunnerTest와 같은 이유로 non-null Collection 자리에 any()·capture()를 그대로 쓰지 않는다.
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
class OrphanImageGcServiceTest {

    @Mock private lateinit var commentRepository: CommentRepository

    @Mock private lateinit var memberRepository: MemberRepository

    @Mock private lateinit var supabaseStorageService: SupabaseStorageService

    @InjectMocks private lateinit var service: OrphanImageGcService

    private val now = Instant.parse("2026-10-05T00:00:00Z")
    private val old = now.minus(Duration.ofHours(25))
    private val recent = now.minus(Duration.ofHours(23))

    private fun url(name: String) = "https://xyz.supabase.co/storage/v1/object/public/comments/$name.png"

    private val referencedUrl = url("referenced")
    private val avatarUrl = url("avatar")

    private fun stubReferences() {
        `when`(commentRepository.findAllContent()).thenReturn(listOf("댓글 내용\n\n$referencedUrl"))
        `when`(memberRepository.findAllImageUrls()).thenReturn(listOf(avatarUrl))
        listOf(referencedUrl, avatarUrl).forEach {
            `when`(supabaseStorageService.isManagedUrl(it)).thenReturn(true)
        }
    }

    @Test
    fun `24시간이 지났고 아무도 쓰지 않는 객체만 지운다`() {
        stubReferences()
        val oldOrphan = url("old-orphan")
        `when`(supabaseStorageService.listAllObjects()).thenReturn(
            listOf(
                StoredObject(referencedUrl, old),
                StoredObject(avatarUrl, old),
                StoredObject(oldOrphan, old),
                // 23시간 전 업로드는 아직 제출 중일 수 있어 남긴다.
                StoredObject(url("recent-orphan"), recent),
                // 생성 시각이 없는 항목(폴더 등)은 판단할 수 없어 남긴다.
                StoredObject(url("no-created-at"), null),
            ),
        )

        val summary = service.collect(dryRun = false, now = now)

        @Suppress("UNCHECKED_CAST")
        val captor = ArgumentCaptor.forClass(Collection::class.java) as ArgumentCaptor<Collection<String>>
        verify(supabaseStorageService).deleteObjectsByPublicUrls(captureValue(captor))
        assertEquals(listOf(oldOrphan), captor.value.toList())
        assertEquals(listOf(oldOrphan), summary.candidates)
        assertEquals(1, summary.deleteRequested)
        assertEquals(5, summary.total)
    }

    @Test
    fun `dry-run이면 후보만 보고하고 지우지 않는다`() {
        stubReferences()
        `when`(supabaseStorageService.listAllObjects()).thenReturn(listOf(StoredObject(url("orphan"), old)))

        val summary = service.collect(dryRun = true, now = now)

        verify(supabaseStorageService, never()).deleteObjectsByPublicUrls(anyCollection())
        assertEquals(listOf(url("orphan")), summary.candidates)
        assertEquals(0, summary.deleteRequested)
    }

    @Test
    fun `참조가 0건인데 후보가 있으면 지우지 않고 중단한다`() {
        `when`(commentRepository.findAllContent()).thenReturn(emptyList())
        `when`(memberRepository.findAllImageUrls()).thenReturn(emptyList())
        `when`(supabaseStorageService.listAllObjects()).thenReturn(listOf(StoredObject(url("orphan"), old)))

        val summary = service.collect(dryRun = false, now = now)

        verify(supabaseStorageService, never()).deleteObjectsByPublicUrls(anyCollection())
        assertTrue(summary.aborted)
    }

    @Test
    fun `한 번 실행에 최대 개수까지만 100개씩 나눠 지운다`() {
        stubReferences()
        val orphans = (1..OrphanImageGcService.MAX_DELETE_PER_RUN + 1).map { StoredObject(url("orphan-$it"), old) }
        `when`(supabaseStorageService.listAllObjects()).thenReturn(orphans)

        val summary = service.collect(dryRun = false, now = now)

        verify(supabaseStorageService, times(OrphanImageGcService.MAX_DELETE_PER_RUN / 100)).deleteObjectsByPublicUrls(anyCollection())
        assertEquals(OrphanImageGcService.MAX_DELETE_PER_RUN, summary.deleteRequested)
        assertEquals(OrphanImageGcService.MAX_DELETE_PER_RUN + 1, summary.candidates.size)
    }
}
