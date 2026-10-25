package ai.myimage.storage

import ai.myimage.config.AppProperties
import ai.myimage.jobs.GeneratedImageRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Deletes image files and metadata older than app.storage.retention-days (0 = keep forever). Job records are kept. */
@Component
class RetentionJob(
    private val images: GeneratedImageRepository,
    private val storage: ImageStorage,
    private val props: AppProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 30 3 * * *")
    fun purge() {
        val days = props.storage.retentionDays
        if (days <= 0) return
        val cutoff = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        var total = 0
        while (true) {
            val batch = images.findByCreatedAtBefore(cutoff, PageRequest.of(0, 200))
            if (batch.isEmpty()) break
            var deleted = 0
            for (img in batch) {
                try {
                    storage.delete(img.storageKey)
                    images.delete(img)
                    deleted++
                } catch (e: Exception) {
                    log.warn("Retention: could not delete image {}", img.id)
                }
            }
            total += deleted
            if (deleted == 0) break // avoid spinning on persistent failures
        }
        if (total > 0) log.info("Retention: deleted {} image(s) older than {} day(s)", total, days)
    }
}
