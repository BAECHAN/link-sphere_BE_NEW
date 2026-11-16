package com.example.linksphere.domain.category

import com.example.linksphere.global.exception.CategoryNotFoundException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.mockito.junit.jupiter.MockitoExtension

/**
 * 다른 도메인용 조회(글은 카테고리를 id로만 들고 이름·slug는 여기에 묻는다)와 getCategoryBySlug의 "없음" 처리를 다룬다.
 * getAllCategories는 이 파일의 범위 밖이다.
 */
@ExtendWith(MockitoExtension::class)
class CategoryServiceTest {

    @Mock private lateinit var categoryRepository: CategoryRepository

    @InjectMocks private lateinit var categoryService: CategoryService

    private val dev = TableCategory(id = 1L, name = "개발", slug = "dev")
    private val design = TableCategory(id = 2L, name = "디자인", slug = "design")

    @Test
    fun `getCategoryInfosByIds 는 엔티티 대신 id·name·slug만 돌려준다`() {
        `when`(categoryRepository.findAllByIdIn(setOf(1L, 2L))).thenReturn(listOf(dev, design))

        val result = categoryService.getCategoryInfosByIds(setOf(1L, 2L))

        assertEquals(listOf(CategoryInfo(1L, "개발", "dev"), CategoryInfo(2L, "디자인", "design")), result)
    }

    @Test
    fun `getCategoryInfosByIds 는 빈 목록이면 저장소를 부르지 않는다`() {
        assertEquals(emptyList<CategoryInfo>(), categoryService.getCategoryInfosByIds(emptyList()))

        verifyNoInteractions(categoryRepository)
    }

    // 글 등록·수정에서 없는 카테고리 id는 오류 없이 버린다(예전 getCategoriesByIds와 같은 동작)
    @Test
    fun `filterExistingIds 는 실제로 있는 id만 돌려준다`() {
        `when`(categoryRepository.findAllByIdIn(listOf(1L, 999L))).thenReturn(listOf(dev))

        assertEquals(setOf(1L), categoryService.filterExistingIds(listOf(1L, 999L)))
    }

    @Test
    fun `resolveIds 는 이름이나 slug가 맞는 카테고리의 id를 돌려준다`() {
        val names = listOf("개발", "design")
        `when`(categoryRepository.findAllByNameInOrSlugIn(names, names)).thenReturn(listOf(dev, design))

        assertEquals(setOf(1L, 2L), categoryService.resolveIds(names))
    }

    // 빈 집합은 "거르지 않음"이 아니라 "결과 없음"이다 - 구분은 호출하는 쪽(PostService)이 한다
    @Test
    fun `resolveIds 는 맞는 카테고리가 없으면 빈 집합을 돌려준다`() {
        val names = listOf("없는카테고리")
        `when`(categoryRepository.findAllByNameInOrSlugIn(names, names)).thenReturn(emptyList())

        assertEquals(emptySet<Long>(), categoryService.resolveIds(names))
    }

    // 예전에는 IllegalArgumentException의 공통 매핑으로 404 NOT_FOUND였다 - 무엇이 없는지 code로 알 수 있게 한다
    @Test
    fun `getCategoryBySlug 는 slug가 없으면 CategoryNotFoundException`() {
        `when`(categoryRepository.findBySlug("missing")).thenReturn(null)

        assertThrows(CategoryNotFoundException::class.java) { categoryService.getCategoryBySlug("missing") }
    }
}
