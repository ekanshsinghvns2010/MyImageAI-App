package ai.myimage.config

import ai.myimage.jobs.JobQueue
import ai.myimage.jobs.LocalJobQueue
import ai.myimage.provider.ImageGenerationProvider
import ai.myimage.provider.MockProvider
import ai.myimage.provider.PythonHttpProvider
import ai.myimage.storage.ImageStorage
import ai.myimage.storage.LocalImageStorage
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path

/**
 * Chooses the pluggable implementations from configuration.
 * Adding a provider/storage/queue = add a class and one `when` branch here.
 */
@Configuration
class WiringConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun imageGenerationProvider(props: AppProperties): ImageGenerationProvider =
        when (val p = props.ai.provider.trim().lowercase()) {
            "mock" -> {
                log.warn("AI_PROVIDER=mock: images are SIMULATED placeholders, not real AI output.")
                MockProvider()
            }
            "python-http", "remote-http" -> PythonHttpProvider(props.ai, p)
            else -> throw IllegalStateException(
                "Unsupported AI_PROVIDER '$p'. Supported values: mock, python-http, remote-http."
            )
        }

    @Bean
    fun imageStorage(props: AppProperties): ImageStorage =
        when (val t = props.storage.type.trim().lowercase()) {
            "local" -> LocalImageStorage(Path.of(props.storage.localPath))
            else -> throw IllegalStateException(
                "Unsupported STORAGE_TYPE '$t'. Only 'local' is implemented; implement ImageStorage for S3 (see README)."
            )
        }

    @Bean
    fun jobQueue(): JobQueue = LocalJobQueue()

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info().title("MyImageAI API").version("v1")
                .description("REST API for text-to-image generation with the MyImageAI model.")
        )
        .components(
            Components().addSecuritySchemes(
                "apiKey",
                SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                    .description("API key sent as 'Authorization: Bearer <key>' (or the X-API-Key header).")
            )
        )
        .addSecurityItem(SecurityRequirement().addList("apiKey"))
}
