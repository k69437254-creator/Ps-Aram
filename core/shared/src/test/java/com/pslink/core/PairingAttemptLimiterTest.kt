package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAttemptLimiterTest {
    @Test
    fun limitsFailuresPerSourceAndExpiresThemAfterWindow() {
        val limiter = PairingAttemptLimiter(maxFailures = 2, windowMillis = 100L)

        assertTrue(limiter.isAllowed("192.0.2.1", 0L))
        limiter.recordFailure("192.0.2.1", 0L)
        limiter.recordFailure("192.0.2.1", 10L)
        assertFalse(limiter.isAllowed("192.0.2.1", 20L))
        assertTrue(limiter.isAllowed("192.0.2.2", 20L))
        assertTrue(limiter.isAllowed("192.0.2.1", 100L))
    }

    @Test
    fun successfulPairingClearsFailures() {
        val limiter = PairingAttemptLimiter(maxFailures = 2, windowMillis = 100L)
        limiter.recordFailure("192.0.2.1", 0L)
        limiter.recordSuccess("192.0.2.1")

        assertTrue(limiter.isAllowed("192.0.2.1", 1L))
    }

    @Test
    fun boundsTrackedSourcesWithoutEvictingExistingLocks() {
        val limiter = PairingAttemptLimiter(maxFailures = 1, windowMillis = 100L, maxTrackedSources = 2)
        limiter.recordFailure("192.0.2.1", 0L)
        limiter.recordFailure("192.0.2.2", 1L)

        assertFalse(limiter.isAllowed("192.0.2.1", 2L))
        assertFalse(limiter.isAllowed("192.0.2.2", 2L))
    }

    @Test
    fun successfulOverflowPairingDoesNotClearCollidingSourcesFailures() {
        val limiter = PairingAttemptLimiter(
            maxFailures = 2,
            windowMillis = 100L,
            maxTrackedSources = 34
        )
        limiter.recordFailure("regular-a", 0L)
        limiter.recordFailure("regular-b", 0L)
        // These distinct strings have the same Java hash modulo 32.
        limiter.recordFailure("overflow-0", 1L)
        limiter.recordFailure("overflow-0", 2L)
        limiter.recordFailure("overflow-60", 3L)

        limiter.recordSuccess("overflow-0")

        assertFalse(limiter.isAllowed("overflow-60", 4L))
        assertFalse(limiter.isAllowed("overflow-0", 4L))
    }

    @Test
    fun collidingOverflowSourcesCannotAlternateSuccessToBypassLimit() {
        val limiter = PairingAttemptLimiter(
            maxFailures = 2,
            windowMillis = 100L,
            maxTrackedSources = 34
        )
        limiter.recordFailure("regular-a", 0L)
        limiter.recordFailure("regular-b", 0L)
        limiter.recordFailure("overflow-0", 1L)
        limiter.recordFailure("overflow-60", 2L)
        limiter.recordSuccess("overflow-60")
        limiter.recordFailure("overflow-0", 3L)

        assertFalse(limiter.isAllowed("overflow-0", 4L))
        assertFalse(limiter.isAllowed("overflow-60", 4L))
    }

    @Test
    fun overflowBucketsRemainBoundedAndExpireTheirFailures() {
        val limiter = PairingAttemptLimiter(
            maxFailures = 1,
            windowMillis = 100L,
            maxTrackedSources = 34
        )
        limiter.recordFailure("regular-a", 0L)
        limiter.recordFailure("regular-b", 0L)
        repeat(500) { limiter.recordFailure("overflow-$it", it.toLong()) }

        assertFalse(limiter.isAllowed("overflow-0", 99L))
        assertTrue(limiter.isAllowed("overflow-0", 10_000L))
        val tracked = PairingAttemptLimiter::class.java
            .getDeclaredField("failuresBySource")
            .apply { isAccessible = true }
            .get(limiter) as Map<*, *>
        assertTrue(tracked.size <= 34)
    }

    @Test
    fun expiredExactSourcesDoNotForceNewCollidingSourceIntoOverflow() {
        val limiter = PairingAttemptLimiter(
            maxFailures = 1,
            windowMillis = 100L,
            maxTrackedSources = 34
        )
        limiter.recordFailure("regular-a", 0L)
        limiter.recordFailure("regular-b", 0L)
        limiter.recordFailure("overflow-0", 50L)

        // overflow-0 and overflow-60 collide modulo 32. The Exact entries have expired,
        // so the newcomer should use a free Exact slot instead of inheriting that bucket.
        assertTrue(limiter.isAllowed("overflow-60", 100L))
    }

    @Test
    fun manyDistinctCollidingSourcesRespectExactAndOverflowKeyBounds() {
        val maxTrackedSources = 40
        val overflowBucketCount = 32
        val normalSourceLimit = maxTrackedSources - overflowBucketCount
        val limiter = PairingAttemptLimiter(
            maxFailures = 1,
            windowMillis = 60_000L,
            maxTrackedSources = maxTrackedSources
        )

        repeat(10_000) { index ->
            limiter.recordFailure("distinct-source-$index", index.toLong())
        }

        val tracked = PairingAttemptLimiter::class.java
            .getDeclaredField("failuresBySource")
            .apply { isAccessible = true }
            .get(limiter) as Map<*, *>
        val exactCount = tracked.keys.count { it!!::class.java.simpleName == "Exact" }
        val overflowCount = tracked.keys.count { it!!::class.java.simpleName == "Overflow" }

        assertEquals(normalSourceLimit, exactCount)
        assertEquals(overflowBucketCount, overflowCount)
        assertTrue(tracked.size <= maxTrackedSources)
        assertEquals(exactCount + overflowCount, tracked.size)
    }

    @Test
    fun monotonicCounterWrapNearLongMaxExpiresAtExactWindowWithoutEarlyExpiry() {
        val limiter = PairingAttemptLimiter(maxFailures = 1, windowMillis = 5L)
        limiter.recordFailure("source", Long.MAX_VALUE - 2L)

        assertFalse(limiter.isAllowed("source", Long.MIN_VALUE + 1L))
        assertTrue(limiter.isAllowed("source", Long.MIN_VALUE + 2L))
    }

    @Test
    fun elapsedWindowNearLongMinExpiresAtExactThreshold() {
        val limiter = PairingAttemptLimiter(maxFailures = 1, windowMillis = 5L)
        limiter.recordFailure("source", Long.MIN_VALUE)

        assertFalse(limiter.isAllowed("source", Long.MIN_VALUE + 4L))
        assertTrue(limiter.isAllowed("source", Long.MIN_VALUE + 5L))
    }

    @Test
    fun backwardsTimestampDoesNotExpireFailureAndMonotonicTimeLaterExpiresIt() {
        val limiter = PairingAttemptLimiter(maxFailures = 1, windowMillis = 5L)
        limiter.recordFailure("source", 100L)

        assertFalse(limiter.isAllowed("source", 50L))
        assertTrue(limiter.isAllowed("source", 105L))
    }

}
