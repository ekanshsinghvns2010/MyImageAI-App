package ai.myimage.jobs

import ai.myimage.common.ApiException
import ai.myimage.config.AppProperties
import ai.myimage.models.ModelRegistry
import ai.myimage.models.ValidatedGeneration
import ai.myimage.provider.ImageGenerationProvider
import ai.myimage.security.ClientPrincipal
import ai.myimage.storage.SignedUrlService
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

@Service
class JobService(
    private val repo: GenerationJobRepository,
    private val imageRepo: GeneratedImageRepository,
    private val state: JobStateService,
    private val queue: JobQueue,
    private val models: ModelRegistry,
    private val provider: ImageGenerationProvider,
    private val signer: SignedUrlService,
    private val props: AppProperties,
) {
    fun submit(p: ClientPrincipal, req: GenerationRequest, idempotencyKey: String?, wait: Boolean): GenerationResponse {
        val v = models.validate(req)

        if (idempotencyKey != null) {
            repo.findByClientIdAndIdempotencyKey(p.id, idempotencyKey)?.let { return toResponse(checkSame(it, v)) }
        }

        val active = repo.countByClientIdAndStatusIn(p.id, listOf(JobStatus.QUEUED, JobStatus.PROCESSING))
        if (active >= p.maxConcurrentJobs) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS, "CONCURRENCY_LIMIT_EXCEEDED",
                "This API key may have at most ${p.maxConcurrentJobs} unfinished jobs at once.",
                headers = mapOf("Retry-After" to "5")
            )
        }
        val startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
        if (repo.countByClientIdAndCreatedAtAfter(p.id, startOfDay) >= p.dailyQuota) {
            throw ApiException(
                HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED",
                "Daily quota of ${p.dailyQuota} generation requests reached. It resets at 00:00 UTC.",
                headers = mapOf("Retry-After" to "3600")
            )
        }

        val job = GenerationJob(
            clientId = p.id, modelId = v.model.id, prompt = v.prompt, negativePrompt = v.negativePrompt,
            width = v.width, height = v.height, steps = v.steps, seed = v.seed, numImages = v.numImages,
            idempotencyKey = idempotencyKey, simulated = if (provider.simulated) true else null,
        )
        val saved = try {
            repo.save(job)
        } catch (e: DataIntegrityViolationException) {
            // Two concurrent requests with the same Idempotency-Key: the loser returns the winner's job.
            val existing = idempotencyKey?.let { repo.findByClientIdAndIdempotencyKey(p.id, it) } ?: throw e
            return toResponse(checkSame(existing, v))
        }
        queue.enqueue(saved.id)
        return toResponse(if (wait) awaitTerminal(saved) else saved)
    }

    private fun checkSame(existing: GenerationJob, v: ValidatedGeneration): GenerationJob {
        if (existing.modelId != v.model.id || existing.prompt != v.prompt) {
            throw ApiException(
                HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_CONFLICT",
                "This Idempotency-Key was already used with a different request."
            )
        }
        return existing
    }

    /** Bounded wait for short jobs; the HTTP request never waits longer than app.jobs.sync-wait-seconds. */
    private fun awaitTerminal(job: GenerationJob): GenerationJob {
        val deadline = System.nanoTime() + props.jobs.syncWaitSeconds * 1_000_000_000L
        var current = job
        while (!current.status.terminal && System.nanoTime() < deadline) {
            Thread.sleep(150)
            current = repo.findById(job.id).orElse(current)
        }
        return current
    }

    private fun load(p: ClientPrincipal, id: UUID): GenerationJob =
        repo.findByIdAndClientId(id, p.id)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "No generation job with that ID exists for this API key.")

    fun get(p: ClientPrincipal, id: UUID): GenerationResponse = toResponse(load(p, id))

    fun result(p: ClientPrincipal, id: UUID): GenerationResult {
        val job = load(p, id)
        if (job.status != JobStatus.COMPLETED) {
            throw ApiException(
                HttpStatus.CONFLICT, "JOB_NOT_COMPLETED", "The job is ${job.status.name}; no result is available.",
                mapOf("status" to job.status.name)
            )
        }
        return GenerationResult(job.id, job.status, job.simulated, imageInfos(job.id))
    }

    fun history(p: ClientPrincipal, page: Int, pageSize: Int, status: JobStatus?): PageResponse<GenerationResponse> {
        if (page < 0) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "page must be zero or greater.")
        val max = props.limits.historyMaxPageSize
        if (pageSize < 1 || pageSize > max) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "page_size must be between 1 and $max.")
        }
        val pageable = PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = if (status != null) repo.findByClientIdAndStatus(p.id, status, pageable) else repo.findByClientId(p.id, pageable)
        return PageResponse(
            result.content.map { toResponse(it, includeImages = false) },
            page, pageSize, result.totalElements, result.totalPages
        )
    }

    /**
     * Only QUEUED jobs can be cancelled. Once the model is running we cannot stop its inference,
     * so we say so (409) rather than pretend.
     */
    fun cancel(p: ClientPrincipal, id: UUID): GenerationResponse {
        val job = load(p, id)
        if (job.status == JobStatus.QUEUED && state.cancelQueued(id)) return toResponse(load(p, id))
        val current = load(p, id)
        when (current.status) {
            JobStatus.CANCELLED -> return toResponse(current)
            JobStatus.PROCESSING -> throw ApiException(
                HttpStatus.CONFLICT, "CANCELLATION_NOT_SUPPORTED",
                "The job is already being processed and the inference backend cannot interrupt it. It will finish or fail on its own."
            )
            else -> throw ApiException(
                HttpStatus.CONFLICT, "JOB_ALREADY_FINISHED", "The job is already ${current.status.name}.",
                mapOf("status" to current.status.name)
            )
        }
    }

    private fun imageInfos(jobId: UUID): List<ImageInfo> =
        imageRepo.findByJobIdOrderByImageIndexAsc(jobId).map {
            val u = signer.urlFor(it.id)
            ImageInfo(it.id, u.url, u.expiresAt, it.mimeType, it.width, it.height, it.sizeBytes)
        }

    private fun toResponse(j: GenerationJob, includeImages: Boolean = true) = GenerationResponse(
        id = j.id, status = j.status, model = j.modelId, prompt = j.prompt, negativePrompt = j.negativePrompt,
        width = j.width, height = j.height, steps = j.steps, seed = j.seed, numImages = j.numImages,
        simulated = j.simulated, attempts = j.attempts, createdAt = j.createdAt,
        startedAt = j.startedAt, completedAt = j.completedAt,
        error = if (j.errorCode != null) JobError(j.errorCode!!, j.errorMessage ?: "") else null,
        images = if (includeImages && j.status == JobStatus.COMPLETED) imageInfos(j.id) else null,
    )
}
