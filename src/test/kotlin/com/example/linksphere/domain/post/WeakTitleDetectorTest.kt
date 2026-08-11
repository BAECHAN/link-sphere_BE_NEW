package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WeakTitleDetectorTest {

    @Test
    fun `isWeak returns true when title is the raw URL`() {
        assertTrue(WeakTitleDetector.isWeak("https://example.com/a", "https://example.com/a"))
    }

    @Test
    fun `isWeak returns true when title is only the hostname`() {
        assertTrue(WeakTitleDetector.isWeak("example.com", "https://www.example.com/a"))
    }

    @Test
    fun `isWeak returns true when title is the site name without TLD`() {
        assertTrue(WeakTitleDetector.isWeak("Example", "https://example.com/a"))
    }

    @Test
    fun `isWeak returns false for a meaningful title`() {
        assertFalse(WeakTitleDetector.isWeak("리액트 19 릴리즈 노트", "https://react.dev/blog"))
    }

    @Test
    fun `isWeak returns true when title is too short to be meaningful`() {
        assertTrue(WeakTitleDetector.isWeak("A", "https://example.com/a"))
    }

    @Test
    fun `isWeak does not throw on a malformed url`() {
        assertFalse(WeakTitleDetector.isWeak("의미 있는 제목입니다", "not a valid url"))
    }
}
