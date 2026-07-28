package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HangulKeyboardConverterTest {

    @Test
    fun `en2ko converts simple mislayout`() {
        assertEquals("네이버", HangulKeyboardConverter.en2ko("spdlqj"))
    }

    @Test
    fun `en2ko carries over jongseong to next syllable`() {
        assertEquals("안녕", HangulKeyboardConverter.en2ko("dkssud"))
    }

    @Test
    fun `en2ko converts consecutive syllables without jongseong`() {
        assertEquals("가나다라", HangulKeyboardConverter.en2ko("rkskekfk"))
    }

    @Test
    fun `en2ko converts a longer phrase`() {
        assertEquals("안녕하세요", HangulKeyboardConverter.en2ko("dkssudgktpdy"))
    }

    @Test
    fun `en2ko combines double vowel`() {
        assertEquals("뭐", HangulKeyboardConverter.en2ko("anj"))
    }

    @Test
    fun `en2ko combines double batchim`() {
        assertEquals("닭", HangulKeyboardConverter.en2ko("ekfr"))
    }

    @Test
    fun `en2ko keeps unmapped characters as original text`() {
        assertEquals("abc123", HangulKeyboardConverter.en2ko("abc123"))
    }

    @Test
    fun `ko2en converts incomplete jamo sequence`() {
        assertEquals("apple", HangulKeyboardConverter.ko2en("메ㅔㅣㄷ"))
    }

    @Test
    fun `ko2en converts leading standalone jamo`() {
        assertEquals("hello", HangulKeyboardConverter.ko2en("ㅗ디ㅣㅐ"))
    }

    @Test
    fun `ko2en decomposes double batchim`() {
        assertEquals("ekfr", HangulKeyboardConverter.ko2en("닭"))
    }

    @Test
    fun `ko2en decomposes double vowel`() {
        assertEquals("anj", HangulKeyboardConverter.ko2en("뭐"))
    }

    @Test
    fun `en2ko and ko2en round-trip`() {
        val original = "안녕하세요"
        assertEquals(original, HangulKeyboardConverter.en2ko(HangulKeyboardConverter.ko2en(original)))
    }

    @Test
    fun `convertIfMislayout converts all-english input to korean`() {
        assertEquals("네이버", HangulKeyboardConverter.convertIfMislayout("spdlqj"))
    }

    @Test
    fun `convertIfMislayout converts all-korean input to english`() {
        assertEquals("apple", HangulKeyboardConverter.convertIfMislayout("메ㅔㅣㄷ"))
    }

    @Test
    fun `convertIfMislayout returns null for mixed input`() {
        assertNull(HangulKeyboardConverter.convertIfMislayout("네이버api"))
    }

    @Test
    fun `convertIfMislayout returns null for input containing digits`() {
        assertNull(HangulKeyboardConverter.convertIfMislayout("abc123"))
    }

    @Test
    fun `convertIfMislayout returns null for blank input`() {
        assertNull(HangulKeyboardConverter.convertIfMislayout("   "))
    }
}
