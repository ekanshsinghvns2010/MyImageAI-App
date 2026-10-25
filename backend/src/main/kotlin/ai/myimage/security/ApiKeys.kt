package ai.myimage.security

import ai.myimage.common.ApiException
import ai.myimage.config.AppProperties
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

enum class ClientRole { ADMIN, CLIENT }

@Entity
@Table(name = "api_keys")
class ApiKey(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 100) var name: String,
    @Column(name = "key_prefix", nullable = false, length = 16) var keyPrefix: String,
    @Column(name = "key_hash", nullable = false, length = 64, unique = true) var keyHash: String,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var role: ClientRole,
    @Column(name = "rate_limit_per_minute", nullable = false) var rateLimitPerMinute: Int,
    @Column(name = "daily_quota", nullable = false) var dailyQuota: Int,
    @Column(name = "max_concurrent_jobs", nullable = false) var maxConcurrentJobs: Int,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
    @Column(name = "revoked_at") var revokedAt: Instant? = null,
)

interface ApiKeyRepository : JpaRepository<ApiKey, UUID> {
    fun findByKeyHash(keyHash: String): ApiKey?
    fun findAllByOrderByCreatedAtDesc(): List<ApiKey>
}

/** The authenticated caller, available in controllers via @AuthenticationPrincipal. */
data class ClientPrincipal(
    val id: UUID,
    val name: String,
    val role: ClientRole,
    val rateLimitPerMinute: Int,
    val dailyQuota: Int,
    val maxConcurrentJobs: Int,
)

data class CreatedKey(val entity: ApiKey, val secret: String)

@Service
class ApiKeyService(
    private val repo: ApiKeyRepository,
    private val props: AppProperties,
) {
    private val random = SecureRandom()

    /**
     * Secrets are 256-bit random values, so a fast unsalted SHA-256 is appropriate
     * (there is nothing to brute-force, unlike human-chosen passwords).
     */
    fun hash(secret: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)))

    fun create(
        name: String,
        role: ClientRole,
        rateLimit: Int?,
        dailyQuota: Int?,
        maxConcurrent: Int?,
    ): CreatedKey {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val secret = "mia_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val entity = repo.save(
            ApiKey(
                name = name.trim(),
                keyPrefix = secret.take(8),
                keyHash = hash(secret),
                role = role,
                rateLimitPerMinute = rateLimit ?: props.security.defaultRateLimitPerMinute,
                dailyQuota = dailyQuota ?: props.security.defaultDailyQuota,
                maxConcurrentJobs = maxConcurrent ?: props.security.defaultMaxConcurrentJobs,
            )
        )
        return CreatedKey(entity, secret)
    }

    /** Returns the key if it exists and is not revoked. Checked on every request, so revocation is immediate. */
    fun authenticate(secret: String): ApiKey? =
        repo.findByKeyHash(hash(secret))?.takeIf { it.revokedAt == null }

    fun list(): List<ApiKey> = repo.findAllByOrderByCreatedAtDesc()

    fun revoke(id: UUID): ApiKey {
        val key = repo.findById(id).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "No API key with that ID exists.")
        }
        if (key.revokedAt == null) {
            key.revokedAt = Instant.now()
            repo.save(key)
        }
        return key
    }

    fun ensureBootstrapAdmin(secret: String) {
        val h = hash(secret)
        if (repo.findByKeyHash(h) == null) {
            repo.save(
                ApiKey(
                    name = "bootstrap-admin",
                    keyPrefix = "bootstrap",
                    keyHash = h,
                    role = ClientRole.ADMIN,
                    rateLimitPerMinute = props.security.defaultRateLimitPerMinute,
                    dailyQuota = props.security.defaultDailyQuota,
                    maxConcurrentJobs = props.security.defaultMaxConcurrentJobs,
                )
            )
        }
    }
}

/** Creates the first administrator key from ADMIN_BOOTSTRAP_API_KEY. There is no public key-creation endpoint. */
@Component
class AdminBootstrap(
    private val keys: ApiKeyService,
    private val props: AppProperties,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val secret = props.security.adminBootstrapKey?.trim().orEmpty()
        when {
            secret.isEmpty() ->
                log.warn("ADMIN_BOOTSTRAP_API_KEY is not set: no admin key exists unless one was created earlier.")
            secret.length < 32 || secret.startsWith("REPLACE_ME") ->
                log.error("ADMIN_BOOTSTRAP_API_KEY is a placeholder or shorter than 32 characters; it was NOT registered.")
            else -> {
                keys.ensureBootstrapAdmin(secret)
                log.info("Bootstrap admin key is registered.")
            }
        }
    }
}
