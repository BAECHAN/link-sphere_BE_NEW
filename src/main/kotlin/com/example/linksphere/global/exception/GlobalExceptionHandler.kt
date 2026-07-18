package com.example.linksphere.global.exception

import com.example.linksphere.global.common.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {

    private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(PostNotFoundException::class)
    fun handlePostNotFoundException(e: PostNotFoundException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                code = "POST_NOT_FOUND",
                message = e.message ?: "Post not found",
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbiddenException(e: ForbiddenException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.FORBIDDEN.value(),
                code = "FORBIDDEN",
                message = e.message ?: "Access denied",
            )
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response)
    }

    @ExceptionHandler(DuplicateMemberException::class)
    fun handleDuplicateMemberException(
        e: DuplicateMemberException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "DUPLICATE_MEMBER",
                message = e.message ?: "Duplicate member",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
    }

    @ExceptionHandler(BookmarkFolderNotFoundException::class)
    fun handleBookmarkFolderNotFoundException(
        e: BookmarkFolderNotFoundException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                code = "FOLDER_NOT_FOUND",
                message = e.message ?: "Folder not found",
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
    }

    @ExceptionHandler(BookmarkNotFoundException::class)
    fun handleBookmarkNotFoundException(
        e: BookmarkNotFoundException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                code = "BOOKMARK_NOT_FOUND",
                message = e.message ?: "Bookmark not found",
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
    }

    @ExceptionHandler(DuplicateFolderNameException::class)
    fun handleDuplicateFolderNameException(
        e: DuplicateFolderNameException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "DUPLICATE_FOLDER_NAME",
                message = e.message ?: "Folder name already exists",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
    }

    @ExceptionHandler(InvalidCredentialsException::class)
    fun handleInvalidCredentialsException(
        e: InvalidCredentialsException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                code = "INVALID_CREDENTIALS",
                message = e.message ?: "Invalid credentials",
            )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestCookieException::class)
    fun handleMissingRequestCookieException(
        _e: org.springframework.web.bind.MissingRequestCookieException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                code = "MISSING_REFRESH_TOKEN",
                message = "Refresh token is missing",
            )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
    }

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidTokenException(e: InvalidTokenException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                code = "INVALID_REFRESH_TOKEN",
                message = e.message ?: "Invalid token",
            )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
    }

    @ExceptionHandler(io.jsonwebtoken.JwtException::class)
    fun handleJwtException(e: io.jsonwebtoken.JwtException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                code = "INVALID_REFRESH_TOKEN",
                message = "Token validation failed: ${e.message}",
            )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                code = "NOT_FOUND",
                message = e.message ?: "Not found",
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
    }

    @ExceptionHandler(InvalidInputException::class)
    fun handleInvalidInputException(e: InvalidInputException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_INPUT",
                message = e.message ?: "Invalid input",
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException::class)
    fun handleMethodArgumentTypeMismatchException(
        e: org.springframework.web.method.annotation.MethodArgumentTypeMismatchException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_PATH_VARIABLE",
                message = "Invalid value for parameter '${e.name}': ${e.value}",
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException::class)
    fun handleHttpMessageNotReadableException(
        e: org.springframework.http.converter.HttpMessageNotReadableException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_REQUEST_BODY",
                message = "Invalid request body",
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    // DB UNIQUE 등 무결성 제약 위반 — Service 의 사전 체크와 DB 제약 사이 race condition 까지 흡수
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException::class)
    fun handleDataIntegrityViolationException(
        e: org.springframework.dao.DataIntegrityViolationException,
    ): ResponseEntity<ErrorResponse> {
        logger.warn("Data integrity violation: {}", e.mostSpecificCause.message)
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "DUPLICATE_RESOURCE",
                message = "이미 존재하는 항목입니다.",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
    }

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unhandled exception", e)
        val response =
            ErrorResponse(
                status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
                code = "INTERNAL_SERVER_ERROR",
                message = "Internal Server Error",
            )
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }
}
