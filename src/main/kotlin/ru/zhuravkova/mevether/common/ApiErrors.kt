package ru.zhuravkova.mevether.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.BindException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.ErrorResponse
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.Instant

data class ApiError(
    val status: Int,
    val code: String,
    val message: String,
    val path: String,
    val timestamp: Instant = Instant.now(),
    val details: Map<String, String> = emptyMap()
)

@RestControllerAdvice
class ApiErrors {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(BusinessException::class)
    fun business(error: BusinessException, request: HttpServletRequest): ResponseEntity<ApiError> =
        response(error.status, error.code, error.message, request)

    @ExceptionHandler(BindException::class)
    fun validation(error: BindException, request: HttpServletRequest): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError(400, "VALIDATION_FAILED", "Request validation failed", request.requestURI,
            details = error.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "Invalid value") }))

    @ExceptionHandler(HttpMessageNotReadableException::class, MethodArgumentTypeMismatchException::class, ConstraintViolationException::class)
    fun malformed(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> =
        response(400, "BAD_REQUEST", "Malformed request or invalid parameter", request)

    @ExceptionHandler(AccessDeniedException::class)
    fun denied(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> =
        response(403, "FORBIDDEN", "Access denied", request)

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> =
        response(404, "NOT_FOUND", "Resource not found", request)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        logger.error("Database integrity constraint rejected a request")
        return response(409, "INTEGRITY_CONFLICT", "Operation conflicts with existing data", request)
    }

    @ExceptionHandler(DataAccessException::class)
    fun database(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        logger.error("Database operation failed: {}", error.javaClass.simpleName)
        return response(503, "DATABASE_UNAVAILABLE", "Database operation temporarily unavailable", request)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception, request: HttpServletRequest): ResponseEntity<ApiError> {
        if (error is ErrorResponse) return response(error.statusCode.value(), "HTTP_ERROR", "Request cannot be processed", request)
        logger.error("Request failed: {}", error.javaClass.simpleName)
        return response(500, "INTERNAL_ERROR", "Internal server error", request)
    }

    private fun response(status: Int, code: String, message: String, request: HttpServletRequest) =
        ResponseEntity.status(status).body(ApiError(status, code, message, request.requestURI))
}
