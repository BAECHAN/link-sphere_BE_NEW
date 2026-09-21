package com.example.linksphere.domain.interaction

import com.example.linksphere.domain.auth.jwt.JwtTokenProvider
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [BookmarkFolderController::class],
    excludeFilters =
    [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [SecurityFilterChain::class],
        ),
    ],
)
class BookmarkFolderControllerTest {

    @Autowired private lateinit var mockMvc: MockMvc

    @MockitoBean private lateinit var bookmarkFolderService: BookmarkFolderService

    @MockitoBean private lateinit var jwtTokenProvider: JwtTokenProvider

    @Test
    @WithMockUser
    fun `getFolders 는 인증된 principal 이 UUID 로 파싱되지 않으면 500 으로 응답한다`() {
        // @WithMockUser 기본 username("user")은 UUID가 아니다 - Security가 인증은
        // 보장하지만(.anyRequest().authenticated()) principal 파싱이 실패하는, 있으면
        // 안 되는 내부 불변조건 위반 케이스를 재현한다. IllegalArgumentException(→404)이
        // 아니라 IllegalStateException(전용 핸들러 없이 catch-all → 500)으로 응답해야
        // 정확하다 - 2026-09-21 이 컨트롤러를 포함한 4곳에서 정정됨.
        mockMvc.perform(get("/bookmark/folders"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
    }
}
