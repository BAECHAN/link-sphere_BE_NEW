package com.example.linksphere.domain.post

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

// 파라미터가 nullable인 이유는 AuthRateLimitRepository.kt 상단 주석과 같다 - Mockito any() 매처가
// Kotlin non-null 파라미터에서 NPE를 내기 때문이다. 호출부(LinkPreviewService)는 실값만 넘긴다.
interface LinkPreviewRepository : JpaRepository<TableLinkPreview, String> {

    // 같은 URL을 처음 동시에 미리보기해도 PK 충돌 없이 마지막 결과가 남도록 upsert한다
    // (AuthRateLimitRepository.incrementHit과 같은 INSERT ON CONFLICT 선례). tags는 호스트명뿐이라
    // 쉼표가 들어갈 일이 없어 쉼표로 이어 넘기고 string_to_array로 배열로 되돌린다.
    @Modifying
    @Query(
        value = "INSERT INTO link_previews (url_hash, url, title, description, og_image, tags, page_content, fetched_at) " +
            "VALUES (:urlHash, :url, :title, :description, :ogImage, string_to_array(:tags, ','), :pageContent, :fetchedAt) " +
            "ON CONFLICT (url_hash) DO UPDATE SET url = EXCLUDED.url, title = EXCLUDED.title, " +
            "description = EXCLUDED.description, og_image = EXCLUDED.og_image, tags = EXCLUDED.tags, " +
            "page_content = EXCLUDED.page_content, fetched_at = EXCLUDED.fetched_at",
        nativeQuery = true,
    )
    fun upsert(
        @Param("urlHash") urlHash: String?,
        @Param("url") url: String?,
        @Param("title") title: String?,
        @Param("description") description: String?,
        @Param("ogImage") ogImage: String?,
        @Param("tags") tags: String?,
        @Param("pageContent") pageContent: String?,
        @Param("fetchedAt") fetchedAt: Instant?,
    )
}
