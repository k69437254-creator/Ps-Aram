package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConnectionSlotLimiterTest {
    @Test
    fun rejectsBeyondCapacityAndReleasesEachSlotOnce() {
        val limiter = ConnectionSlotLimiter(1)
        val slot = limiter.tryAcquire()

        assertNotNull(slot)
        assertNull(limiter.tryAcquire())
        slot!!.close()
        slot.close()
        assertNotNull(limiter.tryAcquire())
    }

    @Test
    fun concurrentAcquisitionNeverExceedsCapacity() {
        val capacity = 3
        val limiter = ConnectionSlotLimiter(capacity)
        val gate = CountDownLatch(1)
        val acquired = CountDownLatch(capacity)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val highWatermark = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val workers = List(12) {
            Thread {
                try {
                    gate.await()
                    val slot = limiter.tryAcquire() ?: return@Thread
                    val current = active.incrementAndGet()
                    highWatermark.updateAndGet { maxOf(it, current) }
                    acquired.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    active.decrementAndGet()
                    slot.close()
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error)
                }
            }.apply { start() }
        }

        gate.countDown()
        assertTrue(acquired.await(5, TimeUnit.SECONDS))
        assertTrue(highWatermark.get() <= capacity)
        release.countDown()
        workers.forEach { it.join() }

        failure.get()?.let { throw AssertionError("Connection slot worker failed", it) }
        assertEquals(0, active.get())
        assertEquals(0, limiter.activeConnections)
        val finalSlot = limiter.tryAcquire()
        assertNotNull(finalSlot)
        finalSlot!!.close()
    }
}
