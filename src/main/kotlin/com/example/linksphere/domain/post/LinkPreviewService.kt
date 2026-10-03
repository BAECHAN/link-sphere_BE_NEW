package com.example.linksphere.domain.post

import com.example.linksphere.global.common.SecureToken
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant

/**
 * 작성 중 링크 미리보기 결과를 잠깐 보관했다가 등록·수정 때 재사용한다. Lambda 칸끼리는 메모리를
 * 공유하지 않으므로 메모리 캐시가 아니라 DB 테이블(link_previews)에 둔다 - 미리보기를 처리한 칸과
 * 등록을 처리한 칸이 달라도 같은 결과를 본다.
 */
@Service
class LinkPreviewService(
    private val linkPreviewRepository: LinkPreviewRepository,
    private val urlMetadataExtractor: UrlMetadataExtractor,
) {
    companion object {
        // 미리보기를 보고 제목·카테고리를 고른 뒤 등록하기까지 충분하고, 그사이 페이지가
        // 바뀌어 낡은 결과가 저장될 위험은 작게 유지하는 길이(계획 문서 PR2 참고).
        val FRESH_FOR: Duration = Duration.ofMinutes(10)
    }

    /** 10분 이내 미리보기 결과가 있으면 돌려주고, 없으면 null(호출부가 직접 크롤링한다). */
    @Transactional(readOnly = true)
    fun findFresh(url: String): UrlMetadata? = linkPreviewRepository.findByIdOrNull(SecureToken.hash(url))
        ?.takeIf { it.fetchedAt.isAfter(Instant.now().minus(FRESH_FOR)) }
        ?.toMetadata()

    /** 캐시가 있으면 그대로, 없거나 만료됐으면 크롤링해 저장한 뒤 돌려준다. URL 검증은 호출부 몫이다. */
    @Transactional
    fun get(url: String): UrlMetadata {
        findFresh(url)?.let { return it }

        val metadata = urlMetadataExtractor.extract(url)
        linkPreviewRepository.upsert(
            urlHash = SecureToken.hash(url),
            url = url,
            title = metadata.title,
            description = metadata.description,
            ogImage = metadata.ogImage,
            tags = metadata.tags.joinToString(","),
            pageContent = metadata.pageContent,
            fetchedAt = Instant.now(),
        )
        return metadata
    }
}
