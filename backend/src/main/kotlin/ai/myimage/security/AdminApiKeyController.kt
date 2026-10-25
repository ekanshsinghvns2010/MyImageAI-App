package ai.myimage.security

import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

data class CreateApiKeyRequest(
    @field:NotBlank(message = "name must not be blank")
    @field:Size(max = 100, message = "name must be at most 100 characters")
    val name: String? = null,
    val role: ClientRole? = null,
    @field:Min(1) @field:Max(100000) val rateLimitPerMinute: Int? = null,
    @field:Min(1) @field:Max(10000000) val dailyQuota: Int? = null,
    @field:Min(1) @field:Max(1000) val maxConcurrentJobs: Int? = null,
)

data class ApiKeyResponse(
    val id: UUID,
    val name: String,
    val keyPrefix: String,
    val role: ClientRole,
    val rateLimitPerMinute: Int,
    val dailyQuota: Int,
    val maxConcurrentJobs: Int,
    val createdAt: Instant,
    val revokedAt: Instant?,
)

/** Returned exactly once, at creation. The secret cannot be recovered later. */
data class CreatedApiKeyResponse(
    val id: UUID,
    val name: String,
    val keyPrefix: String,
    val role: ClientRole,
    val rateLimitPerMinute: Int,
    val dailyQuota: Int,
    val maxConcurrentJobs: Int,
    val createdAt: Instant,
    val apiKey: String,
)

@RestController
@RequestMapping("/api/v1/admin/api-keys")
@Tag(name = "Admin: API keys", description = "Requires an ADMIN API key.")
class AdminApiKeyController(private val service: ApiKeyService) {

    @PostMapping
    fun create(@Valid @RequestBody body: CreateApiKeyRequest): ResponseEntity<CreatedApiKeyResponse> {
        val created = service.create(
            body.name!!, body.role ?: ClientRole.CLIENT,
            body.rateLimitPerMinute, body.dailyQuota, body.maxConcurrentJobs
        )
        val k = created.entity
        return ResponseEntity.status(HttpStatus.CREATED).header("Cache-Control", "no-store").body(
            CreatedApiKeyResponse(
                k.id, k.name, k.keyPrefix, k.role, k.rateLimitPerMinute, k.dailyQuota,
                k.maxConcurrentJobs, k.createdAt, created.secret
            )
        )
    }

    @GetMapping
    fun list(): Map<String, List<ApiKeyResponse>> = mapOf("data" to service.list().map { it.toResponse() })

    @DeleteMapping("/{id}")
    fun revoke(@PathVariable("id") id: UUID): ApiKeyResponse = service.revoke(id).toResponse()

    private fun ApiKey.toResponse() = ApiKeyResponse(
        id, name, keyPrefix, role, rateLimitPerMinute, dailyQuota, maxConcurrentJobs, createdAt, revokedAt
    )
}
