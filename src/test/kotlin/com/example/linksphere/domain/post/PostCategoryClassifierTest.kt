package com.example.linksphere.domain.post

import com.example.linksphere.domain.category.CategoryRepository
import com.example.linksphere.domain.category.TableCategory
import com.example.linksphere.infra.ai.GeminiService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

@ExtendWith(MockitoExtension::class)
class PostCategoryClassifierTest {

    @Mock private lateinit var categoryRepository: CategoryRepository

    @Mock private lateinit var geminiService: GeminiService

    @InjectMocks private lateinit var classifier: PostCategoryClassifier

    private val frontend = TableCategory(id = 1, name = "프론트엔드", slug = "frontend")
    private val ai = TableCategory(id = 2, name = "AI", slug = "ai")

    @Test
    fun `태그가 카테고리 name과 매칭되면 AI 호출 없이 반환한다`() {
        `when`(categoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(listOf(frontend, ai))

        val result = classifier.classify("제목", "설명", listOf("youtu.be", "AI", "웹개발"))

        assertEquals(listOf(ai), result)
        verify(geminiService, never()).classifyCategories(anyString(), any(), anyList(), anyList())
    }

    @Test
    fun `태그 매칭이 대소문자를 구분하지 않는다`() {
        `when`(categoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(listOf(frontend, ai))

        val result = classifier.classify("제목", null, listOf("ai"))

        assertEquals(listOf(ai), result)
    }

    @Test
    fun `태그 매칭 실패 시 Gemini 분류로 폴백한다`() {
        `when`(categoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(listOf(frontend, ai))
        `when`(
            geminiService.classifyCategories("리액트 튜토리얼", "설명", listOf("React", "hooks"), listOf("프론트엔드", "AI")),
        ).thenReturn(listOf("프론트엔드"))

        val result = classifier.classify("리액트 튜토리얼", "설명", listOf("React", "hooks"))

        assertEquals(listOf(frontend), result)
    }

    @Test
    fun `Gemini가 마스터에 없는 이름을 반환하면 폐기한다`() {
        `when`(categoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(listOf(frontend, ai))
        `when`(
            geminiService.classifyCategories("제목", "설명", listOf("Kotlin"), listOf("프론트엔드", "AI")),
        ).thenReturn(listOf("백엔드", "데브옵스"))

        val result = classifier.classify("제목", "설명", listOf("Kotlin"))

        assertTrue(result.isEmpty())
    }
}
