package com.example.linksphere.global.config.security

import com.example.linksphere.global.common.ErrorResponse
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component

@Component
class CustomAuthenticationEntryPoint(private val objectMapper: ObjectMapper) :
        AuthenticationEntryPoint {

    override fun commence(
            request: HttpServletRequest,
            response: HttpServletResponse,
            authException: AuthenticationException
    ) {
        val exception = request.getAttribute("exception") as? String
        val errorCode =
                when (exception) {
                    "TOKEN_EXPIRED" -> "TOKEN_EXPIRED"
                    "INVALID_TOKEN" -> "INVALID_TOKEN"
                    else -> "NOT_LOGGED_IN"
                }

        val errorMessage =
                when (errorCode) {
                    "TOKEN_EXPIRED" -> "Access token has expired"
                    "INVALID_TOKEN" -> "Invalid access token"
                    else -> "Authentication required"
                }

        val errorResponse =
                ErrorResponse(
                        status = HttpServletResponse.SC_UNAUTHORIZED,
                        code = errorCode,
                        message = errorMessage
                )

        response.status = HttpServletResponse.SC_UNAUTHORIZED
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(objectMapper.writeValueAsString(errorResponse))
        response.writer.flush()
        response.writer.close()
    }
}
