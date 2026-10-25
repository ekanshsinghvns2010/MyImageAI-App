package ai.myimage.jobs

import ai.myimage.config.AppProperties
import ai.myimage.provider.ImageGenerationProvider
import ai.myimage.provider.ProviderException
import ai.myimage.provider.ProviderRequest
import ai.myimage.provider.ProviderResult
import ai.myimage.storage.ImageRejectedException
import ai.myimage.storage.ImageStorage
import ai.myimage.storage.ImageValidator
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class StoredImage(
    val id: UUID, val storageKey: String, val mimeType: String,
    val width: Int, val height: Int, val sizeBytes: Long,
)

/** All job state transitions. Each is a conditional update, so concurrent cancel/claim/finish cannot corrupt state. */
@Service
class JobStateService(
    private val jobs: GenerationJobRepository,
    private val images: GeneratedImageRepository,
) {
    fun claim(id: UUID): Boolean = jobs.claim(id, JobStatus.QUEUED, JobStatus.PROCESSING, Instant.now()) == 1

    @Transactional
    fun complete(job: GenerationJob, stored: List<StoredImage>, simulated: Boolean, attempts: Int): Boolean {
        val n = jobs.finish(job.id, JobStatus.PROCESSING, JobStatus.COMPLETED, Instant.now(), simulated, attempts, null, null)
        if (n != 1) return false
        val now = Instant.now()
        images.saveAll(stored.mapIndexed { i, s ->
            GeneratedImage(
                id = s.id, jobId = job.id, clientId = job.clientId, imageIndex = i, storageKey = s.storageKey,
                mimeType = s.mimeType, width = s.width, height = s.height, sizeBytes = s.sizeBytes, createdAt = now,
            )
        })
        return true
    }

    fun fail(id: UUID, code: String, message: String, attempts: Int) {
        jobs.finish(id, JobStatus.PROCESSING, JobStatus.FAILED, Instant.now(), null, attempts, code, message.take(500))
    }

    fun cancelQueued(id: UUID): Boolean =
        jobs.finish(id, JobStatus.QUEUED, JobStatus.CANCELLED, Instant.now(), null, 0, null, null) == 1

    fun requeue(id: UUID) {
        jobs.requeue(id, JobStatus.PROCESSING, JobStatus.QUEUED)
    }

    /** Called once at startup: anything left PROCESSING by a previous run goes back to QUEUED. Assumes a single backend instance. */
    fun recoverOnStartup(): List<UUID> {
        jobs.requeueAll(JobStatus.PROCESSING, JobStatus.QUEUED)
        return jobs.findByStatusOrderByCreatedAtAsc(JobStatus.QUEUED).map { it.id }
    }
}

@Component
class JobWorker(
    private val queue: JobQueue,
    private val state: JobStateService,
    private val jobs: GenerationJobRepository,
    private val provider: ImageGenerationProvider,
    private val storage: ImageStorage,
    private val validator: ImageValidator,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private var executor: ExecutorService? = null

    @EventListener(ApplicationReadyEvent::class)
    fun start() {
        if (!running.compareAndSet(false, true)) return
        state.recoverOnStartup().forEach { queue.enqueue(it) }
        val n = props.jobs.workerConcurrency.coerceAtLeast(1)
        val counter = AtomicInteger()
        val ex = Executors.newFixedThreadPool(n) { r -> Thread(r, "image-worker-${counter.incrementAndGet()}") }
        executor = ex
        repeat(n) { ex.execute { loop() } }
        log.info("Started {} image worker thread(s) using provider '{}'", n, provider.name)
    }

    private fun loop() {
        while (running.get()) {
            val id = try {
                queue.poll(Duration.ofSeconds(1))
            } catch (e: InterruptedException) {
                break
            }
            if (id != null) {
                try {
                    process(id)
                } catch (t: Throwable) {
                    log.error("Worker failed while processing job {}", id, t)
                }
            }
        }
    }

    /** Stops taking new jobs, lets running jobs finish (up to the configured wait), then interrupts the rest. */
    @PreDestroy
    fun stop() {
        running.set(false)
        val ex = executor ?: return
        ex.shutdown()
        if (!ex.awaitTermination(props.jobs.shutdownWaitSeconds, TimeUnit.SECONDS)) {
            log.warn("Workers did not finish in {}s; interrupting. Interrupted jobs are re-queued on next start.", props.jobs.shutdownWaitSeconds)
            ex.shutdownNow()
            ex.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    internal fun process(jobId: UUID) {
        if (!state.claim(jobId)) return // cancelled, or already taken
        val job = jobs.findById(jobId).orElse(null) ?: return
        val request = ProviderRequest(
            job.modelId, job.prompt, job.negativePrompt, job.width, job.height, job.steps, job.seed, job.numImages
        )
        var attempt = 0
        while (true) {
            attempt++
            try {
                val result = provider.generate(request)
                val stored = persist(result)
                if (!state.complete(job, stored, result.simulated, attempt)) {
                    stored.forEach { deleteQuietly(it.storageKey) }
                }
                return
            } catch (e: ProviderException) {
                if (e.retryable && attempt < props.jobs.maxAttempts) {
                    log.warn("Job {} attempt {} failed ({}); retrying", jobId, attempt, e.kind)
                    try {
                        Thread.sleep(props.jobs.retryBackoffMillis * attempt)
                    } catch (ie: InterruptedException) {
                        state.requeue(jobId)
                        Thread.currentThread().interrupt()
                        return
                    }
                    continue
                }
                log.warn("Job {} failed after {} attempt(s): {} - {}", jobId, attempt, e.kind, e.message)
                state.fail(jobId, e.kind.code, e.kind.publicMessage, attempt)
                return
            } catch (e: ImageRejectedException) {
                log.warn("Job {} produced an invalid image: {}", jobId, e.message)
                state.fail(jobId, "INVALID_MODEL_OUTPUT", "The model returned an image that failed validation.", attempt)
                return
            } catch (e: InterruptedException) {
                state.requeue(jobId)
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                log.error("Job {} failed unexpectedly", jobId, e)
                state.fail(jobId, "INTERNAL_ERROR", "An unexpected error occurred.", attempt)
                return
            }
        }
    }

    private fun persist(result: ProviderResult): List<StoredImage> {
        val saved = mutableListOf<StoredImage>()
        try {
            for (img in result.images) {
                val v = validator.validate(img.bytes, img.mimeType)
                val key = storage.save(img.bytes, v.extension)
                saved += StoredImage(UUID.randomUUID(), key, v.mimeType, v.width, v.height, v.sizeBytes)
            }
        } catch (e: Exception) {
            saved.forEach { deleteQuietly(it.storageKey) }
            throw e
        }
        return saved
    }

    private fun deleteQuietly(key: String) {
        try {
            storage.delete(key)
        } catch (e: Exception) {
            log.warn("Could not delete orphaned image {}", key)
        }
    }
}
