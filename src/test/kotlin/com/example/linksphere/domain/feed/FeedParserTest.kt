package com.example.linksphere.domain.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class FeedParserTest {

    // parse()는 네트워크를 타지 않는 순수 함수라 UrlMetadataExtractor는 목으로만 채워둔다.
    private val parser = FeedParser(mock(com.example.linksphere.domain.post.UrlMetadataExtractor::class.java))

    @Test
    fun `parses RSS 2_0 items`() {
        val rss =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>Example Feed</title>
                <item>
                  <title>첫 번째 글</title>
                  <link>https://example.com/posts/1</link>
                  <pubDate>Thu, 03 Sep 2026 00:00:00 GMT</pubDate>
                </item>
                <item>
                  <title>두 번째 글</title>
                  <link>https://example.com/posts/2</link>
                </item>
              </channel>
            </rss>
            """.trimIndent()

        val entries = parser.parse(rss)

        assertEquals(2, entries.size)
        assertEquals(FeedEntry("첫 번째 글", "https://example.com/posts/1"), entries[0])
        assertEquals(FeedEntry("두 번째 글", "https://example.com/posts/2"), entries[1])
    }

    @Test
    fun `parses Atom entries and picks the alternate link over other rels`() {
        val atom =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Example Atom Feed</title>
              <entry>
                <title>Atom 글 제목</title>
                <link rel="self" href="https://example.com/feed.atom"/>
                <link rel="alternate" href="https://example.com/atom/1"/>
                <id>urn:uuid:1</id>
              </entry>
            </feed>
            """.trimIndent()

        val entries = parser.parse(atom)

        assertEquals(1, entries.size)
        assertEquals(FeedEntry("Atom 글 제목", "https://example.com/atom/1"), entries[0])
    }

    @Test
    fun `skips items missing a title or link`() {
        val rss =
            """
            <rss version="2.0">
              <channel>
                <item>
                  <title>제목만 있음</title>
                </item>
                <item>
                  <link>https://example.com/no-title</link>
                </item>
                <item>
                  <title>정상 항목</title>
                  <link>https://example.com/ok</link>
                </item>
              </channel>
            </rss>
            """.trimIndent()

        val entries = parser.parse(rss)

        assertEquals(1, entries.size)
        assertEquals(FeedEntry("정상 항목", "https://example.com/ok"), entries[0])
    }
}
