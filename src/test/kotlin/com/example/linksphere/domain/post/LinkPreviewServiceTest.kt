package com.example.linksphere.domain.post

import com.example.linksphere.global.common.SecureToken
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Duration
import java.time.Instant
import java.util.Optional

class LinkPreviewServiceTest {

    private lateinit var repository: LinkPreviewRepository
    private lateinit var extractor: UrlMetadataExtractor
    private lateinit var service: LinkPreviewService

    private val url = "https://example.com/article"
    private val crawled = UrlMetadata(title = "새로 가져온 제목", description = null, ogImage = null, tags = listOf("example.com"), pageContent = "본문")

    @BeforeEach
    fun setUp() {
        repository = mock(LinkPreviewRepository::class.java)
        extractor = mock(UrlMetadataExtractor::class.java)
        service = LinkPreviewService(repository, extractor)
    }

    private fun row(fetchedAt: Instant) = TableLinkPreview(urlHash = SecureToken.hash(url), url = url, title = "캐시된 제목", tags = listOf("example.com"), fetchedAt = fetchedAt)

    @Test
    fun `findFresh는 10분 이내 행이면 그 결과를 돌려준다`() {
        `when`(repository.findById(SecureToken.hash(url))).thenReturn(Optional.of(row(Instant.now().minus(Duration.ofMinutes(9)))))

        assertEquals("캐시된 제목", service.findFresh(url)?.title)
    }

    @Test
    fun `findFresh는 10분이 지난 행이면 null을 돌려준다`() {
        `when`(repository.findById(SecureToken.hash(url))).thenReturn(Optional.of(row(Instant.now().minus(Duration.ofMinutes(11)))))

        assertNull(service.findFresh(url))
    }

    @Test
    fun `get은 신선한 캐시가 있으면 크롤링하지 않는다`() {
        `when`(repository.findById(SecureToken.hash(url))).thenReturn(Optional.of(row(Instant.now())))

        assertEquals("캐시된 제목", service.get(url).title)
        verify(extractor, never()).extract(url)
    }

    @Test
    fun `get은 캐시가 없으면 크롤링해 upsert한다`() {
        `when`(repository.findById(SecureToken.hash(url))).thenReturn(Optional.empty())
        `when`(extractor.extract(url)).thenReturn(crawled)

        assertEquals("새로 가져온 제목", service.get(url).title)
        verify(repository).upsert(any(), any(), any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `get은 만료된 행이면 다시 크롤링해 upsert한다`() {
        `when`(repository.findById(SecureToken.hash(url))).thenReturn(Optional.of(row(Instant.now().minus(Duration.ofHours(1)))))
        `when`(extractor.extract(url)).thenReturn(crawled)

        assertEquals("새로 가져온 제목", service.get(url).title)
        verify(repository).upsert(any(), any(), any(), any(), any(), any(), any(), any())
    }
}
