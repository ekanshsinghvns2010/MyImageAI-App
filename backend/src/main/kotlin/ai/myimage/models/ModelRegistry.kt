package ai.myimage.models

import ai.myimage.common.ApiException
import ai.myimage.config.AppProperties
import ai.myimage.config.AppProperties.ModelConfig
import ai.myimage.jobs.GenerationRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

data class ValidatedGeneration(
    val model: ModelConfig,
    val prompt: String,
    val negativePrompt: String?,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long?,
    val numImages: Int,
)

@Component
class ModelRegistry(props: AppProperties) {
    val models: List<ModelConfig> = props.models.filter { it.enabled }

    init {
        require(models.isNotEmpty()) { "Configure at least one enabled model under app.models." }
        require(models.map { it.id }.toSet().size == models.size) { "Model IDs under app.models must be unique." }
    }

    fun find(id: String): ModelConfig? = models.firstOrNull { it.id == id }

    val default: ModelConfig get() = models.first()

    /** Applies defaults, then checks every parameter against this model's configured limits and capabilities. */
    fun validate(req: GenerationRequest): ValidatedGeneration {
        val model = if (req.model.isNullOrBlank()) default else
            find(req.model) ?: throw ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "Model '${req.model}' is not available.")

        val errors = mutableListOf<Map<String, Any?>>()
        fun err(field: String, message: String) {
            errors += mapOf("field" to field, "message" to message)
        }

        val prompt = req.prompt?.trim().orEmpty()
        if (prompt.isEmpty()) err("prompt", "prompt must not be blank")
        if (prompt.length > model.maxPromptLength) err("prompt", "prompt must be at most ${model.maxPromptLength} characters for this model")

        val negative = req.negativePrompt?.trim()?.takeIf { it.isNotEmpty() }
        if (negative != null && !model.supportsNegativePrompt) err("negative_prompt", "this model does not support negative prompts")
        if (negative != null && negative.length > model.maxPromptLength) err("negative_prompt", "negative_prompt must be at most ${model.maxPromptLength} characters for this model")

        if (req.seed != null && !model.supportsSeed) err("seed", "this model does not support a seed")
        if (req.seed != null && req.seed < 0) err("seed", "seed must be zero or greater")

        val width = req.width ?: model.defaultWidth
        val height = req.height ?: model.defaultHeight
        if (width < model.minWidth || width > model.maxWidth) err("width", "width must be between ${model.minWidth} and ${model.maxWidth}")
        if (height < model.minHeight || height > model.maxHeight) err("height", "height must be between ${model.minHeight} and ${model.maxHeight}")
        val mult = model.dimensionMultiple.coerceAtLeast(1)
        if (width % mult != 0) err("width", "width must be a multiple of $mult")
        if (height % mult != 0) err("height", "height must be a multiple of $mult")

        val steps = req.steps ?: model.defaultSteps
        if (steps < model.minSteps || steps > model.maxSteps) err("steps", "steps must be between ${model.minSteps} and ${model.maxSteps}")

        val numImages = req.numImages ?: 1
        if (numImages < 1 || numImages > model.maxImages) err("num_images", "num_images must be between 1 and ${model.maxImages}")

        if (errors.isNotEmpty()) {
            throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "One or more parameters are invalid.", mapOf("fields" to errors))
        }
        return ValidatedGeneration(model, prompt, negative, width, height, steps, req.seed, numImages)
    }
}
