package com.example.linksphere.domain.post

import com.example.linksphere.global.exception.InvalidInputException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SafeUrlValidatorTest {

    private val safeUrlValidator = SafeUrlValidator()

    @Test
    fun `validate rejects blank url`() {
        assertThrows(InvalidInputException::class.java) { safeUrlValidator.validate("") }
    }

    @Test
    fun `validate rejects non-http scheme`() {
        assertThrows(InvalidInputException::class.java) { safeUrlValidator.validate("file:///etc/passwd") }
    }

    @Test
    fun `validate rejects loopback address`() {
        assertThrows(InvalidInputException::class.java) { safeUrlValidator.validate("http://127.0.0.1/") }
    }

    @Test
    fun `validate rejects localhost hostname`() {
        assertThrows(InvalidInputException::class.java) { safeUrlValidator.validate("http://localhost/") }
    }

    @Test
    fun `validate rejects link-local address (cloud metadata endpoint)`() {
        assertThrows(InvalidInputException::class.java) {
            safeUrlValidator.validate("http://169.254.169.254/latest/meta-data/")
        }
    }

    @Test
    fun `validate rejects site-local private network address`() {
        assertThrows(InvalidInputException::class.java) { safeUrlValidator.validate("http://10.0.0.1/") }
        assertThrows(InvalidInputException::class.java) { safeUrlValidator.validate("http://192.168.1.1/") }
    }

    @Test
    fun `validate accepts a public IP address`() {
        // 리터럴 IP는 DNS 조회 없이 파싱되므로 네트워크 없는 환경에서도 안정적으로 검증 가능하다.
        assertDoesNotThrow { safeUrlValidator.validate("https://8.8.8.8/") }
    }
}
