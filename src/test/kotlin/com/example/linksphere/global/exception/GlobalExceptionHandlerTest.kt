package com.example.linksphere.global.exception

import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.category.CategoryController
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.category.CategoryService
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Accept 헤더 협상 실패가 handleException(500)으로 떨어지지 않고 406으로 나가는지 검증한다.
 * 어느 컨트롤러든 같은 경로를 타므로 의존성이 가장 적은 CategoryController로 확인한다.
 */
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
class GlobalExceptionHandlerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var categoryService: CategoryService

    @MockitoBean private lateinit var memberSessionService: MemberSessionService

    @Test
    @WithMockUser
    fun `다음 헤더가 이어 붙어 해석할 수 없는 Accept면 500 대신 본문 없는 406을 준다`() {
        `when`(categoryService.getAllCategories()).thenReturn(listOf(CategoryResponse(id = 1, name = "백엔드")))

        // 클라이언트가 X-Access-Token 줄 앞에 공백을 붙여 보내 Accept에 접혀 들어온 실제 운영 사례(2026-10-20)와 같은 값
        mockMvc.perform(get("/common/category-option").header(HttpHeaders.ACCEPT, "*/* X-Access-Token: some-token"))
            .andExpect(status().isNotAcceptable)
            .andExpect(content().string(""))
    }

    @Test
    @WithMockUser
    fun `JSON을 받지 않는 Accept면 406을 준다`() {
        `when`(categoryService.getAllCategories()).thenReturn(listOf(CategoryResponse(id = 1, name = "백엔드")))

        mockMvc.perform(get("/common/category-option").header(HttpHeaders.ACCEPT, "application/xml"))
            .andExpect(status().isNotAcceptable)
            .andExpect(content().string(""))
    }
}
