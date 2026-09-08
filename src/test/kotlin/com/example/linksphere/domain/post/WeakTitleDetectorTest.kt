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

    @Test
    fun `isWeak returns true when title is only a separator plus the site name`() {
        // 데이터센터 IP가 og:title 없는 껍데기 페이지를 받으면 <title>이 "- YouTube"로 떨어진다
        // (2026-09-08 실측, bodyTextLength=94). youtu.be처럼 도메인과 사이트명 표기가 달라도 잡는다.
        assertTrue(WeakTitleDetector.isWeak("- YouTube", "https://youtu.be/abc"))
        assertTrue(WeakTitleDetector.isWeak("| GitHub", "https://github.com/a/b"))
        assertTrue(WeakTitleDetector.isWeak("– Naver", "https://naver.com/x"))
        assertTrue(WeakTitleDetector.isWeak("YouTube", "https://www.youtube.com/watch?v=x"))
    }

    @Test
    fun `isWeak returns false when a real title happens to end with a separator and site name`() {
        // 양 끝 구분자만 제거하고 가운데 구분자로는 쪼개지 않으므로, "제목 - 사이트명" 형태의
        // 정상 제목까지 약한 제목으로 오판하지 않는다.
        assertFalse(WeakTitleDetector.isWeak("리액트 19 릴리즈 - React Blog", "https://react.dev/blog"))
        assertFalse(WeakTitleDetector.isWeak("— 개발자의 하루", "https://blog.example.com/1"))
    }
}
