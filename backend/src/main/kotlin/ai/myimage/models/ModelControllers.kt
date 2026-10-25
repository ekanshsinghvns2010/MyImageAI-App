package ai.myimage.models

import ai.myimage.common.ApiException
import ai.myimage.config.AppProperties.ModelConfig
import ai.myimage.provider.ImageGenerationProvider
import ai.myimage.provider.ProviderHealth
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class Range(val min: Int, val max: Int)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val description: String,
    val version: String,
    val provider: String,
    val simulated: Boolean,
    val capabilities: Map<String, Boolean>,
    val limits: Map<String, Any>,
    val defaults: Map<String, Int>,
)

@RestController
@RequestMapping("/api/v1/models")
@Tag(name = "Models")
class ModelController(
    private val registry: ModelRegistry,
    private val provider: ImageGenerationProvider,
) {
    private fun ModelConfig.toInfo() = ModelInfo(
        id = id, displayName = displayName, description = description, version = version,
        provider = provider.name, simulated = provider.simulated,
        capabilities = mapOf("negative_prompt" to supportsNegativePrompt, "seed" to supportsSeed),
        limits = mapOf(
            "width" to Range(minWidth, maxWidth), "height" to Range(minHeight, maxHeight),
            "dimension_multiple" to dimensionMultiple, "steps" to Range(minSteps, maxSteps),
            "max_images" to maxImages, "max_prompt_length" to maxPromptLength,
        ),
        defaults = mapOf("width" to defaultWidth, "height" to defaultHeight, "steps" to defaultSteps),
    )

    @GetMapping
    fun list(): Map<String, List<ModelInfo>> = mapOf("data" to registry.models.map { it.toInfo() })

    @GetMapping("/{modelId}")
    fun get(@PathVariable("modelId") modelId: String): ModelInfo =
        registry.find(modelId)?.toInfo()
            ?: throw ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "Model '$modelId' is not available.")
}

@RestController
@RequestMapping("/api/v1/health")
@Tag(name = "Health")
class HealthController(private val provider: ImageGenerationProvider) {
    @Volatile
    private var cached: Pair<Long, ProviderHealth>? = null

    private fun inferenceHealth(): ProviderHealth {
        val now = System.currentTimeMillis()
        cached?.let { if (now - it.first < 10_000) return it.second }
        val h = provider.health()
        cached = now to h
        return h
    }

    /** Public. Always 200 while the API process runs; the "inference" block says whether the model backend is reachable (cached 10s). */
    @GetMapping
    fun health(): Map<String, Any> {
        val h = inferenceHealth()
        return mapOf(
            "status" to if (h.up) "UP" else "DEGRADED",
            "provider" to provider.name,
            "simulated" to provider.simulated,
            "inference" to mapOf("status" to if (h.up) "UP" else "DOWN", "detail" to h.detail),
        )
    }
}
