package ai.myimage.common

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.stereotype.Component
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** An error that is safe to show to API clients. */
class ApiException(
    val status: HttpStatus,
    val code: String,
    message: String,
    val details: Map<String, Any?>? = null,
    val headers: Map<String, String> = emptyMap(),
) : RuntimeException(message)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ErrorBody(
    val code: String,
    val message: String,
    val requestId: String?,
    val details: Map<String, Any?>? = null,
)

data class ErrorResponse(val error: ErrorBody)

/** Writes the standard error JSON from places outside Spring MVC (servlet filters, security handlers). */
@Component
class ErrorWriter(private val mapper: ObjectMapper) {
    fun write(
        response: HttpServletResponse,
        status: HttpStatus,
        code: String,
        message: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        if (response.isCommitted) return
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        headers.forEach { (k, v) -> response.setHeader(k, v) }
        mapper.writeValue(
            response.writer,
            ErrorResponse(ErrorBody(code, message, MDC.get(CorrelationIdFilter.MDC_KEY)))
        )
    }
}

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun build(
        status: HttpStatus,
        code: String,
        message: String,
        details: Map<String, Any?>? = null,
        headers: Map<String, String> = emptyMap(),
    ): ResponseEntity<ErrorResponse> {
        val h = HttpHeaders()
        headers.forEach { (k, v) -> h.set(k, v) }
        return ResponseEntity.status(status).headers(h)
            .body(ErrorResponse(ErrorBody(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), details)))
    }

    private fun snake(s: String) = Regex("([a-z0-9])([A-Z])").replace(s, "$1_$2").lowercase()

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = build(e.status, e.code, e.message ?: e.code, e.details, e.headers)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalid(e: MethodArgumentNotValidException): ResponseEntity<ErrorResponse> {
        val fields = e.bindingResult.fieldErrors.map {
            mapOf("field" to snake(it.field), "message" to (it.defaultMessage ?: "invalid value"))
        }
        return build(
            HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "One or more parameters are invalid.",
            mapOf("fields" to fields)
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) = build(
        HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
        "The request body is missing, is not valid JSON, or contains unknown or mistyped fields."
    )

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun mismatch(e: MethodArgumentTypeMismatchException) = build(
        HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Parameter '${e.name}' has an invalid value.",
        mapOf("parameter" to e.name)
    )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missing(e: MissingServletRequestParameterException) = build(
        HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Required parameter '${e.parameterName}' is missing.",
        mapOf("parameter" to e.parameterName)
    )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun method(e: HttpRequestMethodNotSupportedException) =
        build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "This HTTP method is not supported here.")

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun mediaType(e: HttpMediaTypeNotSupportedException) =
        build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "Use Content-Type: application/json.")

    @ExceptionHandler(NoResourceFoundException::class)
    fun notFound(e: NoResourceFoundException) =
        build(HttpStatus.NOT_FOUND, "NOT_FOUND", "The requested resource does not exist.")

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ErrorResponse> {
        // Full detail goes to the server log only (with the request ID); the client gets a generic message.
        log.error("Unhandled exception", e)
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred.")
    }
}
