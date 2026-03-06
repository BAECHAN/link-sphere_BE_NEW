package com.example.linksphere.global.exception

import com.example.linksphere.global.common.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.slf4j.LoggerFactory

@RestControllerAdvice
class GlobalExceptionHandler {

        private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

        @ExceptionHandler(PostNotFoundException::class)
        fun handlePostNotFoundException(e: PostNotFoundException): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.NOT_FOUND.value(),
                                code = "POST_NOT_FOUND",
                                message = e.message ?: "Post not found"
                        )
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
        }

        @ExceptionHandler(ForbiddenException::class)
        fun handleForbiddenException(e: ForbiddenException): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.FORBIDDEN.value(),
                                code = "FORBIDDEN",
                                message = e.message ?: "Access denied"
                        )
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response)
        }

        @ExceptionHandler(DuplicateMemberException::class)
        fun handleDuplicateMemberException(
                e: DuplicateMemberException
        ): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.CONFLICT.value(),
                                code = "DUPLICATE_MEMBER",
                                message = e.message ?: "Duplicate member"
                        )
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
        }

        @ExceptionHandler(InvalidCredentialsException::class)
        fun handleInvalidCredentialsException(
                e: InvalidCredentialsException
        ): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.UNAUTHORIZED.value(),
                                code = "INVALID_CREDENTIALS",
                                message = e.message ?: "Invalid credentials"
                        )
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
        }

        @ExceptionHandler(org.springframework.web.bind.MissingRequestCookieException::class)
        fun handleMissingRequestCookieException(
                _e: org.springframework.web.bind.MissingRequestCookieException
        ): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.UNAUTHORIZED.value(),
                                code = "MISSING_REFRESH_TOKEN",
                                message = "Refresh token is missing"
                        )
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
        }

        @ExceptionHandler(InvalidTokenException::class)
        fun handleInvalidTokenException(e: InvalidTokenException): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.UNAUTHORIZED.value(),
                                code = "INVALID_REFRESH_TOKEN",
                                message = e.message ?: "Invalid token"
                        )
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
        }

        @ExceptionHandler(io.jsonwebtoken.JwtException::class)
        fun handleJwtException(e: io.jsonwebtoken.JwtException): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.UNAUTHORIZED.value(),
                                code = "INVALID_REFRESH_TOKEN",
                                message = "Token validation failed: ${e.message}"
                        )
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
        }

        @ExceptionHandler(IllegalArgumentException::class)
        fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
                val response =
                        ErrorResponse(
                                status = HttpStatus.NOT_FOUND.value(),
                                code = "NOT_FOUND",
                                message = e.message ?: "Not found"
                        )
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
        }

        @ExceptionHandler(Exception::class)
        fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
                logger.error("Unhandled exception", e)
                val response =
                        ErrorResponse(
                                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                                code = "INTERNAL_SERVER_ERROR",
                                message = "Internal Server Error"
                        )
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
        }
}
