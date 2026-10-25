package ai.myimage.security

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class RateDecision(val allowed: Boolean, val retryAfterSeconds: Long)

/**
 * Fixed-window, per-key requests-per-minute limiter held in memory.
 * With several backend instances each instance counts separately; use a shared store (e.g. Redis) if you need a global limit.
 */
@Component
class RateLimiter {
    private class Window(val startMs: Long, var count: Int)

    private val windows = ConcurrentHashMap<UUID, Window>()

    fun tryAcquire(id: UUID, limitPerMinute: Int, nowMs: Long = System.currentTimeMillis()): RateDecision {
        var decision = RateDecision(true, 0)
        windows.compute(id) { _, existing ->
            val win = if (existing == null || nowMs - existing.startMs >= 60_000) Window(nowMs, 0) else existing
            if (win.count >= limitPerMinute) {
                decision = RateDecision(false, (win.startMs + 60_000 - nowMs) / 1000 + 1)
            } else {
                win.count++
            }
            win
        }
        return decision
    }
}
