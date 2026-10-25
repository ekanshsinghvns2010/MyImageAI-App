package ai.myimage

import ai.myimage.provider.ImageGenerationProvider
import ai.myimage.provider.ProviderException
import ai.myimage.provider.ProviderHealth
import ai.myimage.provider.ProviderRequest
import ai.myimage.provider.ProviderResult
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(
    properties = [
        "spring.datasource.url=jdbc:h2:mem:failtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "app.storage.local-path=build/test-images-failure",
        "app.security.admin-bootstrap-key=$TEST_ADMIN_KEY",
        "app.security.signing-secret=test-signing-secret-0123456789abcdef0123456789",
    ]
)
@AutoConfigureMockMvc
@Import(ModelFailureTest.FailingProviderConfig::class)
class ModelFailureTest @Autowired constructor(
    private val mvc: MockMvc,
    private val mapper: ObjectMapper,
) {
    @TestConfiguration
    class FailingProviderConfig {
        @Bean
        @Primary
        fun failingProvider(): ImageGenerationProvider = object : ImageGenerationProvider {
            override val name = "failing"
            override val simulated = false
            override fun generate(request: ProviderRequest): ProviderResult =
                throw ProviderException(ProviderException.Kind.FAILED, "internal detail: /models/secret/path.pt exploded")
            override fun health() = ProviderHealth(false, "down")
        }
    }

    @Test
    fun `model failure marks the job FAILED with a safe error and leaks no internals`() {
        val adminCreate = mvc.perform(
            post("/api/v1/admin/api-keys").header("Authorization", "Bearer $TEST_ADMIN_KEY")
                .contentType(MediaType.APPLICATION_JSON).content("""{"name":"c"}""")
        ).andReturn().response.contentAsString
        val key = mapper.readTree(adminCreate).get("api_key").asText()

        val res = mvc.perform(
            post("/api/v1/images/generations?wait=true").header("Authorization", "Bearer $key")
                .contentType(MediaType.APPLICATION_JSON).content("""{"prompt":"anything"}""")
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("FAILED"))
            .andExpect(jsonPath("$.error.code").value("INFERENCE_FAILED"))
            .andReturn().response.contentAsString

        assertFalse(res.contains("secret/path"), "internal error detail must not reach the client")
    }
}
