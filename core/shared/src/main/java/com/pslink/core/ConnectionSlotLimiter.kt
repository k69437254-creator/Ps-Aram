package com.pslink.core

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConnectionSlotLimiter(maxConnections: Int) {
    private val permits: Semaphore
    private val active = AtomicInteger()

    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        permits = Semaphore(maxConnections)
    }

    val activeConnections: Int
        get() = active.get()

    fun tryAcquire(): Slot? {
        if (!permits.tryAcquire()) return null
        active.incrementAndGet()
        return Slot(permits, active)
    }

    class Slot internal constructor(
        private val permits: Semaphore,
        private val active: AtomicInteger
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        override fun close() {
            if (released.compareAndSet(false, true)) {
                active.decrementAndGet()
                permits.release()
            }
        }
    }
}
