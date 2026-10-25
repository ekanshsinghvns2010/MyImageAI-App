package ai.myimage.provider

/** Everything a model backend needs for one generation. Already validated against the model's limits. */
data class ProviderRequest(
    val modelId: String,
    val prompt: String,
    val negativePrompt: String?,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long?,
    val numImages: Int,
)

class ProviderImage(val bytes: ByteArray, val mimeType: String, val seed: Long?)

data class ProviderResult(val images: List<ProviderImage>, val simulated: Boolean)

data class ProviderHealth(val up: Boolean, val detail: String)

class ProviderException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    /** [code] and [publicMessage] are what API clients see; [message] stays in server logs. */
    enum class Kind(val code: String, val publicMessage: String) {
        UNAVAILABLE("MODEL_UNAVAILABLE", "The image model is currently unavailable."),
        TIMEOUT("INFERENCE_TIMEOUT", "The image model did not respond in time."),
        REJECTED("INFERENCE_REJECTED", "The image model rejected the request parameters."),
        FAILED("INFERENCE_FAILED", "Image generation failed."),
    }

    val retryable: Boolean get() = kind == Kind.UNAVAILABLE
}

/**
 * The only thing the rest of the backend knows about a model runtime.
 * Implementations: [MockProvider], [PythonHttpProvider] (also used for "remote-http").
 * To add a local runtime (e.g. ONNX), implement this interface and add a branch in WiringConfig.
 */
interface ImageGenerationProvider {
    val name: String

    /** True if this provider is known to return simulated (non-AI) images. */
    val simulated: Boolean

    /** Blocking call; the job worker runs it on its own threads. Must throw [ProviderException] on failure. */
    fun generate(request: ProviderRequest): ProviderResult

    fun health(): ProviderHealth
}
