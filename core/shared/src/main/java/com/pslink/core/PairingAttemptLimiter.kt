package com.pslink.core

import java.util.ArrayDeque
import java.util.LinkedHashMap

class PairingAttemptLimiter(
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 60_000L,
    private val maxTrackedSources: Int = 1024
) {
    private sealed interface SourceKey {
        data class Exact(val source: String) : SourceKey
        data class Overflow(val bucket: Int) : SourceKey
    }

    private val failuresBySource = LinkedHashMap<SourceKey, ArrayDeque<Long>>()
    private val overflowBucketCount = minOf(32, maxTrackedSources)
    private val normalSourceLimit = maxTrackedSources - overflowBucketCount

    init {
        require(maxFailures > 0)
        require(windowMillis > 0L)
        require(maxTrackedSources > 0)
    }

    /**
     * [nowMillis] must come from a monotonic clock, in the same time domain as
     * [recordFailure]. A wall-clock rollback cannot be distinguished reliably
     * from counter wrap using timestamps alone. The elapsed-time check assumes
     * the monotonic interval is less than half the signed Long range.
     */
    @Synchronized
    fun isAllowed(source: String, nowMillis: Long): Boolean {
        discardExpiredSources(nowMillis)
        val key = sourceKey(source)
        val failures = failuresBySource[key] ?: return true
        return failures.size < maxFailures
    }

    /** Records a failure using monotonic milliseconds, e.g. System.nanoTime() / 1_000_000. */
    @Synchronized
    fun recordFailure(source: String, nowMillis: Long) {
        val normalized = normalizeSource(source)
        if (!failuresBySource.containsKey(SourceKey.Exact(normalized))) {
            discardExpiredSources(nowMillis)
        }
        val key = sourceKey(normalized)
        val failures = failuresBySource[key] ?: run {
            ArrayDeque<Long>().also { failuresBySource[key] = it }
        }
        discardExpired(failures, nowMillis)
        if (failures.size < maxFailures) failures.addLast(nowMillis)
    }

    @Synchronized
    fun recordSuccess(source: String) {
        val key = sourceKey(source)
        // Overflow success leaves the shared bucket intact; its lockout expires with the window.
        if (key is SourceKey.Exact) failuresBySource.remove(key)
    }

    private fun sourceKey(source: String): SourceKey {
        val normalized = normalizeSource(source)
        val exactKey = SourceKey.Exact(normalized)
        if (failuresBySource.containsKey(exactKey)) return exactKey
        if (failuresBySource.size < normalSourceLimit) return exactKey
        val bucket = (normalized.hashCode() and Int.MAX_VALUE) % overflowBucketCount
        return SourceKey.Overflow(bucket)
    }

    private fun discardExpiredSources(nowMillis: Long) {
        val iterator = failuresBySource.entries.iterator()
        while (iterator.hasNext()) {
            val failures = iterator.next().value
            discardExpired(failures, nowMillis)
            if (failures.isEmpty()) iterator.remove()
        }
    }

    private fun discardExpired(failures: ArrayDeque<Long>, nowMillis: Long) {
        while (failures.isNotEmpty()) {
            val oldestFailure: Long = failures.peekFirst() ?: break
            if (!hasWindowElapsed(oldestFailure, nowMillis)) break
            failures.removeFirst()
        }
    }

    private fun hasWindowElapsed(failureMillis: Long, nowMillis: Long): Boolean {
        // Signed subtraction is wrap-safe for monotonic counters when the
        // measured interval is less than half the Long range (true for millis windows).
        return nowMillis - failureMillis >= windowMillis
    }

    private fun normalizeSource(source: String): String =
        source.takeIf { it.isNotBlank() } ?: "<unknown>"
}
