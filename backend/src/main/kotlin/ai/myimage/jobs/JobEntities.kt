package ai.myimage.jobs

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

enum class JobStatus {
    QUEUED, PROCESSING, COMPLETED, FAILED, CANCELLED;

    val terminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

@Entity
@Table(name = "generation_jobs")
class GenerationJob(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "client_id", nullable = false) var clientId: UUID,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: JobStatus = JobStatus.QUEUED,
    @Column(name = "model_id", nullable = false, length = 100) var modelId: String,
    @Column(nullable = false, length = 4000) var prompt: String,
    @Column(name = "negative_prompt", length = 4000) var negativePrompt: String? = null,
    @Column(nullable = false) var width: Int,
    @Column(nullable = false) var height: Int,
    @Column(nullable = false) var steps: Int,
    var seed: Long? = null,
    @Column(name = "num_images", nullable = false) var numImages: Int,
    @Column(name = "idempotency_key", length = 128) var idempotencyKey: String? = null,
    @Column(nullable = false) var attempts: Int = 0,
    @Column(name = "error_code", length = 64) var errorCode: String? = null,
    @Column(name = "error_message", length = 500) var errorMessage: String? = null,
    var simulated: Boolean? = null,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
    @Column(name = "started_at") var startedAt: Instant? = null,
    @Column(name = "completed_at") var completedAt: Instant? = null,
)

@Entity
@Table(name = "generated_images")
class GeneratedImage(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(name = "job_id", nullable = false) var jobId: UUID,
    @Column(name = "client_id", nullable = false) var clientId: UUID,
    @Column(name = "image_index", nullable = false) var imageIndex: Int,
    @Column(name = "storage_key", nullable = false, length = 255) var storageKey: String,
    @Column(name = "mime_type", nullable = false, length = 64) var mimeType: String,
    @Column(nullable = false) var width: Int,
    @Column(nullable = false) var height: Int,
    @Column(name = "size_bytes", nullable = false) var sizeBytes: Long,
    @Column(name = "created_at", nullable = false) var createdAt: Instant = Instant.now(),
)

interface GenerationJobRepository : JpaRepository<GenerationJob, UUID> {
    fun findByIdAndClientId(id: UUID, clientId: UUID): GenerationJob?
    fun findByClientIdAndIdempotencyKey(clientId: UUID, idempotencyKey: String): GenerationJob?
    fun countByClientIdAndStatusIn(clientId: UUID, statuses: Collection<JobStatus>): Long
    fun countByClientIdAndCreatedAtAfter(clientId: UUID, after: Instant): Long
    fun findByClientId(clientId: UUID, pageable: Pageable): Page<GenerationJob>
    fun findByClientIdAndStatus(clientId: UUID, status: JobStatus, pageable: Pageable): Page<GenerationJob>
    fun findByStatusOrderByCreatedAtAsc(status: JobStatus): List<GenerationJob>

    /** Atomic compare-and-set used by workers to claim a job. Returns 1 if this caller won. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update GenerationJob j set j.status = :to, j.startedAt = :now where j.id = :id and j.status = :from")
    fun claim(
        @Param("id") id: UUID, @Param("from") from: JobStatus, @Param("to") to: JobStatus, @Param("now") now: Instant,
    ): Int

    /** Atomic move to a terminal state (COMPLETED, FAILED, CANCELLED) only if the job is still in [from]. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(
        "update GenerationJob j set j.status = :to, j.completedAt = :now, j.simulated = :simulated, " +
            "j.attempts = :attempts, j.errorCode = :errorCode, j.errorMessage = :errorMessage " +
            "where j.id = :id and j.status = :from"
    )
    fun finish(
        @Param("id") id: UUID, @Param("from") from: JobStatus, @Param("to") to: JobStatus, @Param("now") now: Instant,
        @Param("simulated") simulated: Boolean?, @Param("attempts") attempts: Int,
        @Param("errorCode") errorCode: String?, @Param("errorMessage") errorMessage: String?,
    ): Int

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update GenerationJob j set j.status = :to, j.startedAt = null where j.id = :id and j.status = :from")
    fun requeue(@Param("id") id: UUID, @Param("from") from: JobStatus, @Param("to") to: JobStatus): Int

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update GenerationJob j set j.status = :to, j.startedAt = null where j.status = :from")
    fun requeueAll(@Param("from") from: JobStatus, @Param("to") to: JobStatus): Int
}

interface GeneratedImageRepository : JpaRepository<GeneratedImage, UUID> {
    fun findByJobIdOrderByImageIndexAsc(jobId: UUID): List<GeneratedImage>
    fun findByCreatedAtBefore(cutoff: Instant, pageable: Pageable): List<GeneratedImage>
}
