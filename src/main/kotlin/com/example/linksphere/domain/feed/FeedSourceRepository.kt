package com.example.linksphere.domain.feed

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface FeedSourceRepository : JpaRepository<TableFeedSource, UUID> {
    fun findAllByEnabledTrue(): List<TableFeedSource>
}
