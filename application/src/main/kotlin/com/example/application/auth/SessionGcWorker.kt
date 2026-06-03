package com.example.application.auth

import com.example.domain.auth.AuthSessionPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(SessionGcWorker::class.java)

/**
 * Periodically purges auth sessions whose `expires_at` lapsed more than
 * [retentionMs] ago. Rotation keeps superseded rows around for reuse
 * detection, and logout/chain-burn leaves revoked rows behind; without a
 * sweep they accumulate forever. Keying the purge on expiry (plus a grace
 * window) is safe: an expired token is already rejected by the refresh
 * path, so deleting its row loses no security signal.
 */
class SessionGcWorker(
    private val sessions: AuthSessionPort,
    private val intervalMs: () -> Long,
    private val retentionMs: () -> Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    suspend fun run() {
        try {
            while (true) {
                delay(intervalMs())
                purgeOnce()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error("SessionGcWorker crashed; worker stopped permanently", e)
        }
    }

    internal fun purgeOnce(): Int {
        val removed = sessions.deleteExpiredBefore(clock() - retentionMs())
        if (removed > 0) {
            log.info("Purged {} auth sessions expired more than {} ms ago", removed, retentionMs())
        }
        return removed
    }
}
