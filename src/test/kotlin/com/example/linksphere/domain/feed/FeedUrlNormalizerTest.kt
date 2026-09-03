package com.example.linksphere.domain.feed

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FeedUrlNormalizerTest {

    @Test
    fun `strips utm_ and known tracking params`() {
        val a = FeedUrlNormalizer.normalize("https://example.com/a?utm_source=rss&utm_medium=feed")
        val b = FeedUrlNormalizer.normalize("https://example.com/a?fbclid=xyz")
        assertEquals(a, b)
        assertEquals("https://example.com/a", a)
    }

    @Test
    fun `treats http and https as identical`() {
        assertEquals(
            FeedUrlNormalizer.normalize("http://example.com/a"),
            FeedUrlNormalizer.normalize("https://example.com/a"),
        )
    }

    @Test
    fun `strips leading www and lowercases host`() {
        assertEquals(
            FeedUrlNormalizer.normalize("https://example.com/a"),
            FeedUrlNormalizer.normalize("https://WWW.Example.com/a"),
        )
    }

    @Test
    fun `strips trailing slash but keeps root slash`() {
        assertEquals(
            FeedUrlNormalizer.normalize("https://example.com/a"),
            FeedUrlNormalizer.normalize("https://example.com/a/"),
        )
        assertEquals("https://example.com/", FeedUrlNormalizer.normalize("https://example.com/"))
    }

    @Test
    fun `strips fragment`() {
        assertEquals(
            FeedUrlNormalizer.normalize("https://example.com/a"),
            FeedUrlNormalizer.normalize("https://example.com/a#section"),
        )
    }

    @Test
    fun `sorts remaining query params by name`() {
        assertEquals(
            FeedUrlNormalizer.normalize("https://example.com/a?b=2&a=1"),
            FeedUrlNormalizer.normalize("https://example.com/a?a=1&b=2"),
        )
    }

    @Test
    fun `returns original string on malformed url instead of throwing`() {
        val malformed = "not a valid url ::"
        assertEquals(malformed, FeedUrlNormalizer.normalize(malformed))
    }
}
