package com.example.linksphere.global.exception

import com.example.linksphere.global.common.ErrorResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
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

    @ExceptionHandler(CommentNotFoundException::class)
    fun handleCommentNotFoundException(e: CommentNotFoundException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                code = "COMMENT_NOT_FOUND",
                message = e.message ?: "Comment not found",
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
    }

    @ExceptionHandler(CommentDeletedException::class)
    fun handleCommentDeletedException(e: CommentDeletedException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "COMMENT_DELETED",
                message = e.message ?: "Comment was deleted",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
    }

    @ExceptionHandler(UnsupportedImageTypeException::class)
    fun handleUnsupportedImageTypeException(e: UnsupportedImageTypeException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "UNSUPPORTED_IMAGE_TYPE",
                message = e.message ?: "Unsupported image type",
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
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

    @ExceptionHandler(DuplicateNicknameException::class)
    fun handleDuplicateNicknameException(
        e: DuplicateNicknameException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "DUPLICATE_NICKNAME",
                message = e.message ?: "Duplicate nickname",
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

    // refresh에 실린 토큰의 회원이 쿠키 주인과 다름 - FE는 그 탭만 로그아웃한다(FE client.ts handleTokenExpired).
    // INVALID_REFRESH_TOKEN으로 내면 FE가 일반 갱신 실패로 보고 공유 세션 플래그까지 지운다.
    @ExceptionHandler(SessionMismatchException::class)
    fun handleSessionMismatchException(e: SessionMismatchException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "SESSION_MISMATCH",
                message = e.message ?: "Session mismatch",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
    }

    @ExceptionHandler(EmailNotVerifiedException::class)
    fun handleEmailNotVerifiedException(e: EmailNotVerifiedException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.FORBIDDEN.value(),
                code = "EMAIL_NOT_VERIFIED",
                message = e.message ?: "Email verification required",
            )
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response)
    }

    @ExceptionHandler(InvalidActionTokenException::class)
    fun handleInvalidActionTokenException(e: InvalidActionTokenException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.UNAUTHORIZED.value(),
                code = "INVALID_ACTION_TOKEN",
                message = e.message ?: "Invalid or expired token",
            )
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response)
    }

    @ExceptionHandler(RateLimitExceededException::class)
    fun handleRateLimitExceededException(e: RateLimitExceededException): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.TOO_MANY_REQUESTS.value(),
                code = "RATE_LIMIT_EXCEEDED",
                message = e.message ?: "Too many requests",
            )
        val builder = ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        e.retryAfterSeconds?.let { builder.header(HttpHeaders.RETRY_AFTER, it.toString()) }
        return builder.body(response)
    }

    // 메서드 파라미터(@RequestParam 등)에 직접 붙인 @Size 같은 제약 위반 - @Valid @RequestBody의
    // MethodArgumentNotValidException과 달리 클래스 레벨 @Validated가 있는 컨트롤러에서
    // jakarta.validation.ConstraintViolationException으로 던져진다. 핸들러가 없으면
    // handleException(500)으로 떨어진다.
    @ExceptionHandler(jakarta.validation.ConstraintViolationException::class)
    fun handleConstraintViolationException(
        e: jakarta.validation.ConstraintViolationException,
    ): ResponseEntity<ErrorResponse> {
        val message = e.constraintViolations.joinToString("; ") { "${it.propertyPath}: ${it.message}" }
        logger.warn("Constraint violation: {}", message)
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_INPUT",
                message = message.ifBlank { "Invalid input" },
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
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

    @ExceptionHandler(InvalidUrlException::class)
    fun handleInvalidUrlException(e: InvalidUrlException): ResponseEntity<ErrorResponse> {
        logger.warn("Invalid url ({}): {}", e.code, e.message)
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = e.code,
                message = e.message ?: "Invalid URL",
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    @ExceptionHandler(InvalidInputException::class)
    fun handleInvalidInputException(e: InvalidInputException): ResponseEntity<ErrorResponse> {
        logger.warn("Invalid input: {}", e.message)
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_INPUT",
                message = e.message ?: "Invalid input",
            )
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    // @Valid 검증 실패 - 이 핸들러가 없으면 handleException(500)으로 떨어진다
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(
        e: org.springframework.web.bind.MethodArgumentNotValidException,
    ): ResponseEntity<ErrorResponse> {
        val message = e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        logger.warn("Validation failed: {}", message)
        val response =
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                code = "INVALID_INPUT",
                message = message.ifBlank { "Invalid input" },
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

    // 동시 요청끼리 부딪힌 경우 — 낙관적 락(엔티티로 지우려던 행을 다른 요청이 먼저 지움) · 비관적 락 ·
    // 데드락 실패가 모두 ConcurrencyFailureException 하위다. 서버 결함이 아니라 재시도하면 풀리는
    // 충돌이라 500 대신 409로 돌려준다. 좋아요·북마크 토글은 일괄 DELETE로 바꿔 더는 여기 오지 않고,
    // 엔티티 단위 삭제가 남은 경로(batchDeleteBookmarks 등)의 안전망이다(docs/plans/2026-10-13-like-concurrency.md).
    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException::class)
    fun handleConcurrencyFailureException(
        e: org.springframework.dao.ConcurrencyFailureException,
    ): ResponseEntity<ErrorResponse> {
        logger.warn("Concurrency failure: {}", e.mostSpecificCause.message)
        val response =
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                code = "CONCURRENT_UPDATE",
                message = "다른 요청과 동시에 처리되어 실패했습니다. 다시 시도해 주세요.",
            )
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response)
    }

    // 정적 리소스가 클래스패스에 없을 때(예: 운영에 번들되지 않는 Swagger UI 경로) - 이 핸들러가
    // 없으면 permitAll이라 401은 피해가고 handleException(500)으로 떨어져 "서버 에러"처럼 보인다.
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException::class)
    fun handleNoResourceFoundException(
        e: org.springframework.web.servlet.resource.NoResourceFoundException,
    ): ResponseEntity<ErrorResponse> {
        val response =
            ErrorResponse(
                status = HttpStatus.NOT_FOUND.value(),
                code = "NOT_FOUND",
                message = "Resource not found: ${e.resourcePath}",
            )
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response)
    }

    // Accept 헤더를 해석할 수 없거나(형식이 깨짐) JSON을 받지 않겠다는 요청 - 이 핸들러가 없으면
    // handleException(500)으로 떨어진다. 본문은 싣지 않는다: JSON을 쓰려면 같은 Accept로 다시 협상해야 해
    // 또 실패한다. 헤더 값도 로그에 남기지 않는다 - 클라이언트가 헤더 줄 앞에 공백을 붙여 보내면 다음 헤더
    // (X-Access-Token 등)가 Accept에 이어 붙어 함께 찍힌다(2026-10-20 운영 재현에서 실제로 일어남).
    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException::class)
    fun handleHttpMediaTypeNotAcceptableException(): ResponseEntity<Void> {
        logger.warn("Not acceptable: unparseable or non-JSON Accept header")
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build()
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
