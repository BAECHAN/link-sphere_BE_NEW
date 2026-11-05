package com.example.linksphere.global.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.classic.util.LogbackMDCAdapter
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment

// 프로퍼티 이름과 값 처리기가 실제로 맞물리는지 보려고 Lambda가 쓰는 logstash 인코더를 그대로 태운다.
class EmailMaskingLogCustomizerTest {

    private fun encode(message: String, throwable: Throwable? = null): JsonNode {
        val context = LoggerContext()
        context.mdcAdapter = LogbackMDCAdapter()
        context.putObject(
            Environment::class.java.name,
            MockEnvironment().withProperty("logging.structured.json.customizer", EmailMaskingLogCustomizer::class.java.name),
        )

        val encoder = StructuredLogEncoder()
        encoder.context = context
        encoder.setFormat("logstash")
        encoder.start()

        val event = LoggingEvent(javaClass.name, context.getLogger("test"), Level.ERROR, message, throwable, null)

        return jacksonObjectMapper().readTree(encoder.encode(event))
    }

    @Test
    fun `message의 이메일을 가리고 나머지 문장은 그대로 둔다`() {
        val log = encode("[MailService] 발송 실패(to=user.name+tag@example.co.kr)")

        assertEquals("[MailService] 발송 실패(to=<email>)", log["message"].asText())
    }

    @Test
    fun `stack_trace 속 유니크 위반 상세의 이메일을 가린다`() {
        val log = encode(
            "insert failed",
            IllegalStateException("Detail: Key (lower(email))=(race@example.com) already exists."),
        )

        val stackTrace = log["stack_trace"].asText()
        assertFalse(stackTrace.contains("race@example.com"))
        assertTrue(stackTrace.contains("Key (lower(email))=(<email>)"))
    }

    @Test
    fun `이메일이 아닌 값은 바꾸지 않는다`() {
        val log = encode("user@localhost 요청 처리, 1.2.3.4:443")

        assertEquals("user@localhost 요청 처리, 1.2.3.4:443", log["message"].asText())
    }
}
