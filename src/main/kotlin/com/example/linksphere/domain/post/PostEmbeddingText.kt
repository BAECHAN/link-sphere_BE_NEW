package com.example.linksphere.domain.post

/**
 * 검색 의미 매칭용 임베딩 텍스트 조립 + FloatArray ↔ Postgres vector 리터럴 변환.
 *
 * gemini-embedding-2는 task_type 파라미터가 없다(공식 문서:
 * ai.google.dev/gemini-api/docs/embeddings) - 대신 프롬프트 안에 문서/질의 구분을
 * 지시어로 넣는 방식을 쓴다. 서로 다른 지시어로 만든 벡터도 비교 가능하다고 문서가
 * 보장한다.
 */
object PostEmbeddingText {

    /** 게시글 등록·재수집 시 저장할 문서 임베딩용 텍스트. */
    fun document(post: TablePost): String {
        val body = listOfNotNull(
            post.description,
            post.aiSummary,
            post.tags?.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString("\n")
        return "title: ${post.title} | text: $body"
    }

    /** 검색 시점 질의 임베딩용 텍스트. */
    fun query(search: String): String = "task: search result | query: $search"

    /**
     * Postgres vector 입력 리터럴로 변환. 고정 소수점으로 포맷해 아주 작은 성분값이
     * 과학적 표기(1.0E-7 등)로 나가는 걸 피한다 - pgvector가 허용하는지 직접 확인하지
     * 않았다.
     */
    fun toVectorLiteral(embedding: FloatArray): String = embedding.joinToString(",", prefix = "[", postfix = "]") { "%.8f".format(it) }
}
