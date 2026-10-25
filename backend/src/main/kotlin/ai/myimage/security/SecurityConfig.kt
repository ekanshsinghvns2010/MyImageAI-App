package ai.myimage.security

import ai.myimage.common.ErrorWriter
import ai.myimage.config.AppProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Authenticates "Authorization: Bearer <key>" or "X-API-Key: <key>", then applies the per-key rate limit.
 * Deliberately NOT a Spring bean (a Filter bean would be registered twice); it is added to the security chain below.
 */
class ApiKeyAuthFilter(
    private val keys: ApiKeyService,
    private val limiter: RateLimiter,
    private val errors: ErrorWriter,
) : OncePerRequestFilter() {

    private fun extract(request: HttpServletRequest): String? {
        val auth = request.getHeader("Authorization")
        if (auth != null && auth.startsWith("Bearer ", ignoreCase = true)) {
            return auth.substring(7).trim().takeIf { it.isNotEmpty() }
        }
        return request.getHeader("X-API-Key")?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val secret = extract(request)
        if (secret != null) {
            val key = keys.authenticate(secret)
            if (key == null) {
                errors.write(response, HttpStatus.UNAUTHORIZED, "INVALID_API_KEY", "The API key is invalid or has been revoked.")
                return
            }
            val decision = limiter.tryAcquire(key.id, key.rateLimitPerMinute)
            if (!decision.allowed) {
                errors.write(
                    response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                    "Rate limit of ${key.rateLimitPerMinute} requests per minute exceeded.",
                    mapOf("Retry-After" to decision.retryAfterSeconds.toString())
                )
                return
            }
            val principal = ClientPrincipal(
                key.id, key.name, key.role, key.rateLimitPerMinute, key.dailyQuota, key.maxConcurrentJobs
            )
            SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(
                principal, null, listOf(SimpleGrantedAuthority("ROLE_${key.role.name}"))
            )
        }
        chain.doFilter(request, response)
    }
}

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val keys: ApiKeyService,
    private val limiter: RateLimiter,
    private val errors: ErrorWriter,
    private val props: AppProperties,
) {
    @Bean
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() } // stateless API authenticated by a header secret, no cookies
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .formLogin { it.disable() }
            .httpBasic { it.disable() }
            .logout { it.disable() }
            .authorizeHttpRequests { a ->
                a.requestMatchers(
                    "/actuator/health", "/actuator/health/**", "/api/v1/health",
                    "/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**",
                    "/api/v1/files/**", // protected by expiring HMAC signatures instead of API keys
                ).permitAll()
                    .requestMatchers("/actuator/**", "/api/v1/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll()
            }
            .exceptionHandling { e ->
                e.authenticationEntryPoint { _, res, _ ->
                    errors.write(res, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Provide an API key as 'Authorization: Bearer <key>'.")
                }.accessDeniedHandler { _, res, _ ->
                    errors.write(res, HttpStatus.FORBIDDEN, "INSUFFICIENT_PERMISSIONS", "This API key is not allowed to access this resource.")
                }
            }
            .addFilterBefore(ApiKeyAuthFilter(keys, limiter, errors), UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    /** Explicit allowlist only. An empty list means browsers from other origins are not allowed. */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        val source = UrlBasedCorsConfigurationSource()
        val origins = props.cors.allowedOrigins.map { it.trim() }.filter { it.isNotEmpty() && it != "*" }
        if (origins.isNotEmpty()) {
            val c = CorsConfiguration()
            c.allowedOrigins = origins
            c.allowedMethods = listOf("GET", "POST", "DELETE", "OPTIONS")
            c.allowedHeaders = listOf("Authorization", "Content-Type", "X-API-Key", "Idempotency-Key", "X-Request-Id")
            c.exposedHeaders = listOf("X-Request-Id", "Retry-After")
            c.maxAge = 3600
            source.registerCorsConfiguration("/**", c)
        }
        return source
    }
}
