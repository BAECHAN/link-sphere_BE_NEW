package com.example.linksphere.domain.post

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant

// sql/create_link_previews.sql과 컬럼명 1:1 대응.
@Entity
@Table(name = "link_previews")
class TableLinkPreview(
    @Id @Column(name = "url_hash", nullable = false, length = 64) val urlHash: String,
    @Column(name = "url", nullable = false, columnDefinition = "text") var url: String,
    @Column(name = "title", nullable = false, columnDefinition = "text") var title: String,
    @Column(name = "description", columnDefinition = "text") var description: String? = null,
    @Column(name = "og_image", columnDefinition = "text") var ogImage: String? = null,
    @Column(name = "tags") @JdbcTypeCode(SqlTypes.ARRAY) var tags: List<String>? = null,
    @Column(name = "page_content", columnDefinition = "text") var pageContent: String? = null,
    @Column(name = "fetched_at", nullable = false) var fetchedAt: Instant,
) {
    fun toMetadata() = UrlMetadata(
        title = title,
        description = description,
        ogImage = ogImage,
        tags = tags ?: emptyList(),
        pageContent = pageContent,
    )
}
