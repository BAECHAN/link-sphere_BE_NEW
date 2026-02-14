package com.example.linksphere.infra.ai.dto

data class GeminiRequest(
        val contents: List<Content>,
        val generationConfig: GenerationConfig? = null
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

data class AiAnalysisResult(val summary: String?, val tags: List<String>)
