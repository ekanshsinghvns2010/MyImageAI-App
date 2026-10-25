package ai.myimage.common

import ai.myimage.config.AppProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/** Assigns every request an ID (accepting a sane client-supplied X-Request-Id), exposes it in MDC and the response. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {
    companion object {
        const val HEADER = "X-Request-Id"
        const val MDC_KEY = "requestId"
        private val VALID = Regex("^[A-Za-z0-9._-]{8,64}$")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val incoming = request.getHeader(HEADER)
        val id = if (incoming != null && VALID.matches(incoming)) incoming else UUID.randomUUID().toString()
        MDC.put(MDC_KEY, id)
        response.setHeader(HEADER, id)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }
}

/** Rejects requests that declare a body larger than the configured limit. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestSizeFilter(
    private val props: AppProperties,
    private val errors: ErrorWriter,
) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > props.limits.maxRequestBytes) {
            errors.write(
                response, HttpStatus.PAYLOAD_TOO_LARGE, "REQUEST_TOO_LARGE",
                "The request body exceeds the maximum size of ${props.limits.maxRequestBytes} bytes."
            )
            return
        }
        chain.doFilter(request, response)
    }
}
