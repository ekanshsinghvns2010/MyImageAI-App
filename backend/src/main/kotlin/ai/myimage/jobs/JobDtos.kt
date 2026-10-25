package ai.myimage.jobs

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

/** Optional fields fall back to the model's configured defaults. Fields a model does not support are rejected, not ignored. */
data class GenerationRequest(
    val model: String? = null,
    @field:NotBlank(message = "prompt must not be blank")
    @field:Size(max = 4000, message = "prompt is too long")
    val prompt: String? = null,
    @field:Size(max = 4000, message = "negative_prompt is too long")
    val negativePrompt: String? = null,
    @field:Min(1) @field:Max(16384) val width: Int? = null,
    @field:Min(1) @field:Max(16384) val height: Int? = null,
    @field:Min(1) @field:Max(1000) val steps: Int? = null,
    val seed: Long? = null,
    @field:Min(1) @field:Max(64) val numImages: Int? = null,
)

data class ImageInfo(
    val id: UUID,
    val url: String,
    val urlExpiresAt: Instant,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
)

data class JobError(val code: String, val message: String)

data class GenerationResponse(
    val id: UUID,
    val status: JobStatus,
    val model: String,
    val prompt: String,
    val negativePrompt: String?,
    val width: Int,
    val height: Int,
    val steps: Int,
    val seed: Long?,
    val numImages: Int,
    /** true = images are simulated placeholders, not real AI output; absent until known. */
    val simulated: Boolean?,
    val attempts: Int,
    val createdAt: Instant,
    val startedAt: Instant?,
    val completedAt: Instant?,
    val error: JobError?,
    val images: List<ImageInfo>?,
)

data class GenerationResult(
    val jobId: UUID,
    val status: JobStatus,
    val simulated: Boolean?,
    val images: List<ImageInfo>,
)

data class PageResponse<T>(
    val data: List<T>,
    val page: Int,
    val pageSize: Int,
    val totalElements: Long,
    val totalPages: Int,
)
