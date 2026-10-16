package com.example.linksphere.domain.category

import com.example.linksphere.domain.auth.MemberSessionService
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.HttpHeaders
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [CategoryController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class CategoryControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var categoryService: CategoryService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser
    fun `카테고리 목록은 브라우저가 5분간 그대로 쓰고 7일까지 옛 값을 바로 쓰며 다시 받게 하는 Cache-Control로 응답한다`() {
        `when`(categoryService.getAllCategories()).thenReturn(listOf(CategoryResponse(id = 1, name = "백엔드")))

        // 이 테스트에도 Spring Security 기본 헤더(no-store·Pragma·Expires)가 걸린다 - 컨트롤러 값이 그 대신
        // 나가고 기본값은 하나도 섞이지 않아야 한다
        mockMvc.perform(get("/common/category-option"))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "max-age=300, public, stale-while-revalidate=604800"))
            .andExpect(header().doesNotExist(HttpHeaders.PRAGMA))
            .andExpect(header().doesNotExist(HttpHeaders.EXPIRES))
            .andExpect(jsonPath("$.data[0].name").value("백엔드"))
    }
}
