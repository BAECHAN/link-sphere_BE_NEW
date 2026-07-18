package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PostSearchQueryTest {

    @Test
    fun `tokenize splits on whitespace`() {
        assertEquals(listOf("콜드", "스타트"), PostSearchQuery.tokenize("콜드 스타트"))
    }

    @Test
    fun `tokenize collapses multiple whitespaces and trims`() {
        assertEquals(listOf("콜드", "스타트"), PostSearchQuery.tokenize("  콜드   스타트  "))
    }

    @Test
    fun `tokenize lowercases tokens`() {
        assertEquals(listOf("cold", "start"), PostSearchQuery.tokenize("Cold START"))
    }

    @Test
    fun `tokenize keeps a single compound token`() {
        assertEquals(listOf("콜드스타트"), PostSearchQuery.tokenize("콜드스타트"))
    }

    @Test
    fun `tokenize returns empty list for null`() {
        assertEquals(emptyList<String>(), PostSearchQuery.tokenize(null))
    }

    @Test
    fun `tokenize returns empty list for blank`() {
        assertEquals(emptyList<String>(), PostSearchQuery.tokenize("   "))
    }
}
