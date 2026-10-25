package ai.myimage.storage

import ai.myimage.common.ApiException
import ai.myimage.config.AppProperties
import ai.myimage.jobs.GeneratedImageRepository
import org.slf4j.LoggerFactory
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.file.NoSuchFileException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SignedUrl(val url: String, val expiresAt: Instant)

/** Issues and verifies expiring HMAC-signed image URLs, so image links can be used by <img> tags and bots without sending the API key. */
@Component
class SignedUrlService(props: AppProperties, env: Environment) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val ttl = props.security.signedUrlTtlSeconds
    private val baseUrl = props.security.publicBaseUrl.trim().trimEnd('/')
    private val key: ByteArray

    init {
        val configured = props.security.signingSecret?.trim().orEmpty()
        val usable = configured.length >= 32 && !configured.startsWith("REPLACE_ME")
        key = when {
            usable -> configured.toByteArray(Charsets.UTF_8)
            env.acceptsProfiles(Profiles.of("prod")) ->
                throw IllegalStateException("URL_SIGNING_SECRET must be set (32+ characters) in the prod profile.")
            else -> {
                log.warn("URL_SIGNING_SECRET not set: using a random per-process secret; image URLs stop working after restart.")
                ByteArray(32).also { SecureRandom().nextBytes(it) }
            }
        }
    }

    private fun mac(data: String): String {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    fun urlFor(imageId: UUID): SignedUrl {
        val expires = Instant.now().plusSeconds(ttl)
        val sig = mac("$imageId:${expires.epochSecond}")
        return SignedUrl("$baseUrl/api/v1/files/$imageId?expires=${expires.epochSecond}&signature=$sig", expires)
    }

    fun verify(imageId: UUID, expiresEpoch: Long, signature: String): Boolean {
        if (expiresEpoch < Instant.now().epochSecond) return false
        val expected = mac("$imageId:$expiresEpoch")
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))
    }
}

@RestController
@RequestMapping("/api/v1/files")
class FileController(
    private val images: GeneratedImageRepository,
    private val storage: ImageStorage,
    private val signer: SignedUrlService,
) {
    @GetMapping("/{imageId}")
    fun get(
        @PathVariable("imageId") imageId: UUID,
        @RequestParam("expires") expires: Long,
        @RequestParam("signature") signature: String,
    ): ResponseEntity<ByteArray> {
        if (!signer.verify(imageId, expires, signature)) {
            throw ApiException(HttpStatus.FORBIDDEN, "INVALID_OR_EXPIRED_URL", "This image URL is invalid or has expired. Request the job result again for a fresh URL.")
        }
        val image = images.findById(imageId).orElseThrow {
            ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "The image does not exist or has been deleted.")
        }
        val bytes = try {
            storage.read(image.storageKey)
        } catch (e: NoSuchFileException) {
            throw ApiException(HttpStatus.NOT_FOUND, "IMAGE_NOT_FOUND", "The image does not exist or has been deleted.")
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.mimeType))
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
            .header("X-Content-Type-Options", "nosniff")
            .body(bytes)
    }
}
