package com.example.linksphere.global.exception

import com.example.linksphere.domain.auth.MemberSessionService
import com.example.linksphere.domain.category.CategoryController
import com.example.linksphere.domain.category.CategoryResponse
import com.example.linksphere.domain.category.CategoryService
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.HttpHeaders
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * 예외가 의도한 HTTP 상태·code로 나가는지 실제 MVC 경로로 검증한다 - Accept 협상 실패(406), 지원하지 않는 메서드(405),
 * IllegalArgumentException 안전망(400), 카테고리 없음(404). 어느 컨트롤러든 같은 핸들러를 타므로 의존성이 가장 적은 CategoryController로 확인한다.
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

    // 앱 코드는 IllegalArgumentException을 던지지 않으므로(AppWideArchitectureRulesTest) 여기 오는 건 라이브러리가
    // 잘못된 인자로 던진 것뿐이다(예: page=-1) - 예전의 404 NOT_FOUND가 아니라 입력 오류로 낸다.
    @Test
    @WithMockUser
    fun `IllegalArgumentException은 400 INVALID_INPUT으로 나간다`() {
        `when`(categoryService.getCategoryBySlug("bad")).thenThrow(IllegalArgumentException("Page index must not be less than zero"))

        mockMvc.perform(get("/common/category-option/bad"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `없는 카테고리 slug는 404 CATEGORY_NOT_FOUND로 나간다`() {
        `when`(categoryService.getCategoryBySlug("missing")).thenThrow(CategoryNotFoundException("missing"))

        mockMvc.perform(get("/common/category-option/missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"))
    }

    // 컨트롤러에 없는 메서드는 Spring MVC가 매핑 단계에서 HttpRequestMethodNotSupportedException을 던진다 - 예전에는 Exception
    // 안전망이 먼저 잡아 500이었다(운영 재현: DELETE /api/common/category-option, 2026-10-26). RFC 9110 §10.2.1은 405에
    // Allow 헤더를 필수로 요구한다.
    @Test
    @WithMockUser
    fun `지원하지 않는 메서드는 500이 아니라 Allow 헤더가 있는 405 METHOD_NOT_ALLOWED로 나간다`() {
        mockMvc.perform(delete("/common/category-option").with(csrf()))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(header().string(HttpHeaders.ALLOW, "GET"))
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
    }

    // NoResourceFoundException은 ServletException이라 handleSpringMvcException 대상에도 들지만, 더 구체적인 전용 핸들러가
    // 이겨 기존 응답(404 NOT_FOUND)이 그대로여야 한다
    @Test
    @WithMockUser
    fun `없는 경로는 전용 핸들러가 받아 404 NOT_FOUND로 나간다`() {
        mockMvc.perform(get("/no-such-path"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            // code는 새 핸들러가 받아도 상태 이름이라 같다 - 전용 핸들러만 쓰는 message로 누가 받았는지 가린다
            .andExpect(jsonPath("$.message").value(startsWith("Resource not found: ")))
    }

    // 아래 셋은 IllegalArgumentException 정리(PR3)로 서비스가 새로 던지게 된 예외가 실제로 어떤 응답이 되는지 고정한다 -
    // 빈 댓글(CommentService), 없는 댓글 좋아요(InteractionService), 닉네임 수정 중복(MemberService). 핸들러는
    // 컨트롤러와 무관하게 같으므로 여기서도 CategoryController 경로로 확인한다.
    @Test
    @WithMockUser
    fun `InvalidInputException은 400 INVALID_INPUT으로 나간다`() {
        `when`(categoryService.getCategoryBySlug("empty")).thenThrow(InvalidInputException("Content or image must be provided"))

        mockMvc.perform(get("/common/category-option/empty"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
    }

    @Test
    @WithMockUser
    fun `CommentNotFoundException은 404 COMMENT_NOT_FOUND로 나간다`() {
        `when`(categoryService.getCategoryBySlug("gone")).thenThrow(CommentNotFoundException(UUID.randomUUID()))

        mockMvc.perform(get("/common/category-option/gone"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"))
    }

    @Test
    @WithMockUser
    fun `DuplicateNicknameException은 409 DUPLICATE_NICKNAME으로 나간다`() {
        `when`(categoryService.getCategoryBySlug("dup")).thenThrow(DuplicateNicknameException("takenNick"))

        mockMvc.perform(get("/common/category-option/dup"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("DUPLICATE_NICKNAME"))
    }
}
