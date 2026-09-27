package com.example.linksphere.domain.post

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class PostEmbeddingTextTest {

    private fun post(
        title: String = "제목",
        description: String? = null,
        aiSummary: String? = null,
        tags: List<String>? = null,
    ) = TablePost(
        userId = UUID.randomUUID(),
        url = "https://example.com",
        title = title,
        description = description,
        aiSummary = aiSummary,
        tags = tags,
    )

    @Test
    fun `document는 제목과 설명·요약·태그를 합친다`() {
        val result = PostEmbeddingText.document(
            post(title = "Zustand 입문", description = "설명입니다", aiSummary = "요약입니다", tags = listOf("React", "상태관리")),
        )

        assertEquals("title: Zustand 입문 | text: 설명입니다\n요약입니다\nReact, 상태관리", result)
    }

    @Test
    fun `document는 설명·요약·태그가 없으면 제목만 남긴다`() {
        val result = PostEmbeddingText.document(post(title = "제목만 있는 글"))

        assertEquals("title: 제목만 있는 글 | text: ", result)
    }

    @Test
    fun `document는 빈 태그 목록을 건너뛴다`() {
        val result = PostEmbeddingText.document(post(title = "제목", description = "설명", tags = emptyList()))

        assertEquals("title: 제목 | text: 설명", result)
    }

    @Test
    fun `query는 검색 지시어를 앞에 붙인다`() {
        assertEquals("task: search result | query: 상태관리", PostEmbeddingText.query("상태관리"))
    }

    @Test
    fun `toVectorLiteral은 고정 소수점 vector 리터럴을 만든다`() {
        assertEquals("[0.10000000,0.20000000]", PostEmbeddingText.toVectorLiteral(floatArrayOf(0.1f, 0.2f)))
    }

    @Test
    fun `toVectorLiteral은 아주 작은 값도 과학적 표기 없이 나타낸다`() {
        val literal = PostEmbeddingText.toVectorLiteral(floatArrayOf(0.0000001f))

        assertEquals("[0.00000010]", literal)
    }
}
