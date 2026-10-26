package com.example.linksphere.domain.post

import com.example.linksphere.global.exception.InvalidUrlException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SafeUrlValidatorTest {

    private val safeUrlValidator = SafeUrlValidator()

    @Test
    fun `validate rejects blank url`() {
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("") }
    }

    @Test
    fun `validate rejects non-http scheme`() {
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("file:///etc/passwd") }
    }

    @Test
    fun `validate rejects loopback address`() {
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://127.0.0.1/") }
    }

    @Test
    fun `validate rejects localhost hostname`() {
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://localhost/") }
    }

    @Test
    fun `validate rejects link-local address (cloud metadata endpoint)`() {
        assertThrows(InvalidUrlException::class.java) {
            safeUrlValidator.validate("http://169.254.169.254/latest/meta-data/")
        }
    }

    @Test
    fun `validate rejects site-local private network address`() {
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://10.0.0.1/") }
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://192.168.1.1/") }
    }

    // JDK isSiteLocalAddress가 보지 않는 대역 - IPv6 ULA(fc00::/7)와 CGNAT(100.64.0.0/10). 경계 바로 밖은 통과해야 한다
    @Test
    fun `validate rejects IPv6 unique local and carrier-grade NAT addresses`() {
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://[fd00::1]/") }
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://[fc00::1]/") }
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://100.64.0.1/") }
        assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate("http://100.127.255.254/") }
        assertDoesNotThrow { safeUrlValidator.validate("http://100.63.255.255/") }
        assertDoesNotThrow { safeUrlValidator.validate("http://100.128.0.1/") }
        assertDoesNotThrow { safeUrlValidator.validate("http://[fbff:ffff:ffff:ffff:ffff:ffff:ffff:ffff]/") }
        assertDoesNotThrow { safeUrlValidator.validate("http://[fe00::1]/") }
        assertDoesNotThrow { safeUrlValidator.validate("http://[2001:4860:4860::8888]/") }
    }

    @Test
    fun `validate accepts a public IP address`() {
        // 리터럴 IP는 DNS 조회 없이 파싱되므로 네트워크 없는 환경에서도 안정적으로 검증 가능하다.
        assertDoesNotThrow { safeUrlValidator.validate("https://8.8.8.8/") }
    }

    @Test
    fun `validate는 원인별 code를 붙인다 - 형식·스킴은 INVALID_URL, 내부망은 URL_NOT_ALLOWED`() {
        fun codeOf(url: String) = assertThrows(InvalidUrlException::class.java) { safeUrlValidator.validate(url) }.code

        assertEquals(InvalidUrlException.INVALID_URL, codeOf(""))
        assertEquals(InvalidUrlException.INVALID_URL, codeOf("ftp://example.com/"))
        assertEquals(InvalidUrlException.INVALID_URL, codeOf("http:///no-host"))
        assertEquals(InvalidUrlException.URL_NOT_ALLOWED, codeOf("http://127.0.0.1/"))
        assertEquals(InvalidUrlException.URL_NOT_ALLOWED, codeOf("http://10.0.0.1/"))
    }

    @Test
    fun `validate는 DNS로 찾을 수 없는 host에 URL_UNRESOLVABLE을 붙인다`() {
        // .invalid TLD는 RFC 2606이 절대 해석되지 않도록 예약한 이름이라 네트워크 상태와 무관하게 실패한다.
        val e =
            assertThrows(InvalidUrlException::class.java) {
                safeUrlValidator.validate("https://no-such-host.invalid/")
            }

        assertEquals(InvalidUrlException.URL_UNRESOLVABLE, e.code)
    }
}
