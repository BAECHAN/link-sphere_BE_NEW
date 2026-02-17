package com.example.linksphere.global.exception

import com.example.linksphere.global.common.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(DuplicateMemberException::class)
    fun handleDuplicateMemberException(e: DuplicateMemberException): ResponseEntity<ErrorResponse> {
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

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        val response =
                ErrorResponse(
                        status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        code = "INTERNAL_SERVER_ERROR",
                        message = "Internal Server Error"
                )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }
}
