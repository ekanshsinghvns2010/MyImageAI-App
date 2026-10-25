package ai.myimage

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

const val TEST_ADMIN_KEY = "test-admin-key-0123456789abcdef0123456789"

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:flowtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "app.ai.provider=mock",
        "app.storage.local-path=build/test-images-flow",
        "app.security.admin-bootstrap-key=$TEST_ADMIN_KEY",
        "app.security.signing-secret=test-signing-secret-0123456789abcdef0123456789",
    ]
)
@AutoConfigureMockMvc
class ApiFlowTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
) {
    private fun bearer(key: String) = "Bearer $key"

    private fun createClientKey(name: String = "test-client", rateLimit: Int? = null): String {
        val body = mutableMapOf<String, Any>("name" to name)
        if (rateLimit != null) body["rate_limit_per_minute"] = rateLimit
        val res = mvc.perform(
            post("/api/v1/admin/api-keys").header("Authorization", bearer(TEST_ADMIN_KEY))
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body))
        ).andExpect(status().isCreated).andReturn()
        return mapper.readTree(res.response.contentAsString).get("api_key").asText()
    }

    private fun json(s: String): JsonNode = mapper.readTree(s)

    @Test
    fun `health is public`() {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk)
            .andExpect(jsonPath("$.simulated").value(true))
    }

    @Test
    fun `missing key gives 401 with standard error body`() {
        mvc.perform(get("/api/v1/models")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.error.request_id").exists())
    }

    @Test
    fun `unknown key gives 401`() {
        mvc.perform(get("/api/v1/models").header("Authorization", bearer("mia_not-a-real-key")))
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"))
    }

    @Test
    fun `client key cannot use admin endpoints`() {
        val key = createClientKey()
        mvc.perform(get("/api/v1/admin/api-keys").header("Authorization", bearer(key)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_PERMISSIONS"))
    }

    @Test
    fun `secret is shown once, never listed, and revocation is immediate`() {
        val created = mvc.perform(
            post("/api/v1/admin/api-keys").header("Authorization", bearer(TEST_ADMIN_KEY))
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"revoke-me"}""")
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        val node = json(created)
        val secret = node.get("api_key").asText()
        val id = node.get("id").asText()

        val listing = mvc.perform(get("/api/v1/admin/api-keys").header("Authorization", bearer(TEST_ADMIN_KEY)))
            .andExpect(status().isOk).andReturn().response.contentAsString
        assertFalse(listing.contains(secret), "the secret must never appear in listings")
        assertFalse(listing.contains("key_hash"))

        mvc.perform(get("/api/v1/models").header("Authorization", bearer(secret))).andExpect(status().isOk)
        mvc.perform(delete("/api/v1/admin/api-keys/$id").header("Authorization", bearer(TEST_ADMIN_KEY)))
            .andExpect(status().isOk)
        mvc.perform(get("/api/v1/models").header("Authorization", bearer(secret)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `per-key rate limit returns 429 with Retry-After`() {
        val key = createClientKey("limited", rateLimit = 2)
        repeat(2) {
            mvc.perform(get("/api/v1/models").header("Authorization", bearer(key))).andExpect(status().isOk)
        }
        mvc.perform(get("/api/v1/models").header("Authorization", bearer(key)))
            .andExpect(status().isTooManyRequests)
            .andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.error.code").value("RATE_LIMIT_EXCEEDED"))
    }

    @Test
    fun `invalid parameters are rejected against model limits`() {
        val key = createClientKey()
        mvc.perform(
            post("/api/v1/images/generations").header("Authorization", bearer(key))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"a fox","width":4096,"height":100,"steps":9999}""")
        ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.error.details.fields").isArray)

        mvc.perform(
            post("/api/v1/images/generations").header("Authorization", bearer(key))
                .contentType(MediaType.APPLICATION_JSON).content("""{"prompt":"   "}""")
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))

        mvc.perform(
            post("/api/v1/images/generations").header("Authorization", bearer(key))
                .contentType(MediaType.APPLICATION_JSON).content("""{"prompt":"x","model":"nope"}""")
        ).andExpect(status().isNotFound).andExpect(jsonPath("$.error.code").value("MODEL_NOT_FOUND"))
    }

    @Test
    fun `full flow - submit, poll, fetch image through signed url, history, isolation`() {
        val key = createClientKey("flow")
        val other = createClientKey("other")

        val created = mvc.perform(
            post("/api/v1/images/generations?wait=true").header("Authorization", bearer(key))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"prompt":"a red fox","negative_prompt":"blurry","width":256,"height":256,"steps":5,"seed":7}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.simulated").value(true))
            .andReturn().response.contentAsString
        val job = json(created)
        val jobId = job.get("id").asText()
        val url = job.get("images").get(0).get("url").asText()

        // Polling and result endpoints
        mvc.perform(get("/api/v1/images/generations/$jobId").header("Authorization", bearer(key)))
            .andExpect(status().isOk).andExpect(jsonPath("$.status").value("COMPLETED"))
        mvc.perform(get("/api/v1/images/generations/$jobId/result").header("Authorization", bearer(key)))
            .andExpect(status().isOk).andExpect(jsonPath("$.images[0].mime_type").value("image/png"))

        // The signed URL serves a real PNG; a tampered signature is refused.
        val bytes = mvc.perform(get(url)).andExpect(status().isOk)
            .andExpect(header().string("Content-Type", "image/png")).andReturn().response.contentAsByteArray
        assertEquals(0x89.toByte(), bytes[0])
        assertEquals('P'.code.toByte(), bytes[1])
        mvc.perform(get(url.dropLast(2) + "xx")).andExpect(status().isForbidden)

        // History lists it; another client cannot see it.
        mvc.perform(get("/api/v1/images/generations/history?page=0&page_size=10").header("Authorization", bearer(key)))
            .andExpect(status().isOk).andExpect(jsonPath("$.total_elements").value(1))
        mvc.perform(get("/api/v1/images/generations/$jobId").header("Authorization", bearer(other)))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"))

        // Finished jobs cannot be cancelled.
        mvc.perform(delete("/api/v1/images/generations/$jobId").header("Authorization", bearer(key)))
            .andExpect(status().isConflict).andExpect(jsonPath("$.error.code").value("JOB_ALREADY_FINISHED"))
    }

    @Test
    fun `idempotency key returns the same job`() {
        val key = createClientKey("idem")
        val body = """{"prompt":"same request","width":256,"height":256,"steps":5}"""
        fun submit() = mvc.perform(
            post("/api/v1/images/generations").header("Authorization", bearer(key)).header("Idempotency-Key", "abc-123")
                .contentType(MediaType.APPLICATION_JSON).content(body)
        ).andReturn().response.contentAsString
        val a = json(submit()).get("id").asText()
        val b = json(submit()).get("id").asText()
        assertEquals(a, b)
        assertTrue(a.isNotBlank())
    }
}
