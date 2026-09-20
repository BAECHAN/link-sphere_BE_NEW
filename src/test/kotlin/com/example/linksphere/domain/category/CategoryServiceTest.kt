package com.example.linksphere.domain.category

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

/**
 * getCategoriesByIds만 다룬다 - PostService가 CategoryRepository를 직접 쓰지 않고 이
 * 메서드를 거치도록 리팩터하면서 추가된 새 코드다. getAllCategories/getCategoryBySlug는
 * 이번 변경으로 건드리지 않아 이 파일의 범위 밖이다.
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
}
