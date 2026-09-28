package com.example.linksphere.global.common

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ClientIpResolverTest {

    @Test
    fun `헤더가 없으면 null을 반환한다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.getHeader("CloudFront-Viewer-Address")).thenReturn(null)

        assertNull(ClientIpResolver.resolve(request))
    }

    @Test
    fun `IPv4 ip-colon-port 형식에서 IP만 뽑아낸다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.getHeader("CloudFront-Viewer-Address")).thenReturn("203.0.113.178:41487")

        assertEquals("203.0.113.178", ClientIpResolver.resolve(request))
    }

    @Test
    fun `IPv6 대괄호 형식에서 IP만 뽑아낸다`() {
        val request = mock(HttpServletRequest::class.java)
        `when`(request.getHeader("CloudFront-Viewer-Address")).thenReturn("[2001:db8::1]:41487")

        assertEquals("2001:db8::1", ClientIpResolver.resolve(request))
    }
}
