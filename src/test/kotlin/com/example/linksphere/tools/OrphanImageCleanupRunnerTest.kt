package com.example.linksphere.tools

import com.example.linksphere.domain.upload.OrphanImageGcService
import com.example.linksphere.domain.upload.OrphanImageGcService.GcSummary
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

// 판정·삭제 로직은 OrphanImageGcServiceTest가 검증한다 - 여기서는 인자 → dryRun 위임만 본다.
@ExtendWith(MockitoExtension::class)
class OrphanImageCleanupRunnerTest {

    @Mock private lateinit var orphanImageGcService: OrphanImageGcService

    @InjectMocks private lateinit var runner: OrphanImageCleanupRunner

    private val summary = GcSummary(total = 0, referenced = 0, candidates = emptyList(), deleteRequested = 0, aborted = false)

    @Test
    fun `dry-run by default does not delete anything`() {
        `when`(orphanImageGcService.collect(dryRun = true)).thenReturn(summary)

        runner.run(emptyArray())

        verify(orphanImageGcService).collect(dryRun = true)
    }

    @Test
    fun `--delete runs the real deletion`() {
        `when`(orphanImageGcService.collect(dryRun = false)).thenReturn(summary)

        runner.run(arrayOf("--delete"))

        verify(orphanImageGcService).collect(dryRun = false)
    }
}
