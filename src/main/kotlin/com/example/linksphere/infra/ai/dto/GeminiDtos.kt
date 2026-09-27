package com.example.linksphere.infra.ai.dto

data class GeminiRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
)

data class Content(val role: String = "user", val parts: List<Part>)

data class Part(val text: String)

data class GenerationConfig(
    val temperature: Double = 0.7,
    val topK: Int = 40,
    val topP: Double = 0.95,
    val maxOutputTokens: Int = 2048,
)

data class GeminiResponse(val candidates: List<Candidate>?)

data class Candidate(val content: Content?, val finishReason: String?, val index: Int?)

data class AiAnalysisResult(
    val summary: String?,
    val tags: List<String>,
    val title: String? = null,
    val description: String? = null,
)

// embedContent 전용 - generateContent와 URL 경로는 같은 패밀리지만 요청 바디는 완전히
// 다르다(공식 문서: ai.google.dev/api/embeddings). generateContent와 달리 모델명을
// URL뿐 아니라 바디에도 "models/{model}" 형태로 함께 보내야 한다.
data class EmbedContentRequest(
    val model: String,
    val content: Content,
    val config: EmbedContentConfig? = null,
)

data class EmbedContentConfig(val outputDimensionality: Int? = null)

data class EmbedContentResponse(val embedding: ContentEmbedding?)

data class ContentEmbedding(val values: List<Float>?)
