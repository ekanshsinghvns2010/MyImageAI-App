package ai.myimage.jobs

import java.time.Duration
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Hand-off between the API and the workers. Job state itself is always in the database, so a queue
 * only carries IDs and can be rebuilt after a restart (see JobWorker.start).
 * To scale out, implement this with Redis (e.g. a list with BRPOP) and add a branch in WiringConfig.
 */
interface JobQueue {
    fun enqueue(jobId: UUID)
    fun poll(timeout: Duration): UUID?
}

class LocalJobQueue : JobQueue {
    private val queue = LinkedBlockingQueue<UUID>()

    override fun enqueue(jobId: UUID) {
        queue.add(jobId)
    }

    override fun poll(timeout: Duration): UUID? = queue.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
}
