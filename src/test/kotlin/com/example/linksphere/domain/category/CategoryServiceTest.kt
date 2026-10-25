package com.example.linksphere.domain.category

import com.example.linksphere.global.exception.CategoryNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

/**
 * getCategoriesByIds(PostService가 CategoryRepository를 직접 쓰지 않고 이 메서드를 거치도록 리팩터하면서
 * 추가된 코드)와 getCategoryBySlug의 "없음" 처리를 다룬다. getAllCategories는 이 파일의 범위 밖이다.
 */
@ExtendWith(MockitoExtension::class)
class CategoryServiceTest {

    @Mock private lateinit var categoryRepository: CategoryRepository

    @InjectMocks private lateinit var categoryService: CategoryService

    @Test
    fun `getCategoriesByIds 는 요청한 id 목록 그대로 리포지토리에 위임한다`() {
        val category = TableCategory(id = 1L, name = "개발", slug = "dev")
        `when`(categoryRepository.findAllByIdIn(listOf(1L, 2L))).thenReturn(listOf(category))

        val result = categoryService.getCategoriesByIds(listOf(1L, 2L))

        assertEquals(listOf(category), result)
    }

    @Test
    fun `getCategoriesByIds 는 빈 목록을 요청하면 빈 목록을 반환한다`() {
        `when`(categoryRepository.findAllByIdIn(emptyList())).thenReturn(emptyList())

        val result = categoryService.getCategoriesByIds(emptyList())

        assertEquals(emptyList<TableCategory>(), result)
    }

    // 예전에는 IllegalArgumentException의 공통 매핑으로 404 NOT_FOUND였다 - 무엇이 없는지 code로 알 수 있게 한다
    @Test
    fun `getCategoryBySlug 는 slug가 없으면 CategoryNotFoundException`() {
        `when`(categoryRepository.findBySlug("missing")).thenReturn(null)

        assertThrows(CategoryNotFoundException::class.java) { categoryService.getCategoryBySlug("missing") }
    }
}
