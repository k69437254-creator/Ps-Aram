package com.pslink.core

import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A bounded queue for media frames. Video queues can supply [isKeyFrame] to
 * discard incomplete reference chains after overflow; audio queues can omit it
 * and evict the oldest samples instead.
 */
class BoundedMediaQueue<T>(
    private val packetCapacity: Int,
    private val byteBudget: Int,
    private val sizeOf: (T) -> Int,
    private val isKeyFrame: ((T) -> Boolean)? = null
) {
    data class Entry<T>(val value: T, val generation: Long)

    enum class OfferResult {
        ENQUEUED,
        RECOVERED_AT_KEY_FRAME,
        DROPPED_UNTIL_KEY_FRAME,
        WAITING_FOR_KEY_FRAME,
        DROPPED_OLDEST,
        REJECTED_INVALID_SIZE,
        REJECTED_OVERSIZED
    }

    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val queue = ArrayDeque<Entry<T>>()
    private var bytes = 0
    private var queueGeneration = 0L
    private var waitingForKeyFrame = isKeyFrame != null

    init {
        require(packetCapacity > 0) { "packetCapacity must be positive" }
        require(byteBudget > 0) { "byteBudget must be positive" }
    }

    val queuedBytes: Int
        get() = lock.withLock { bytes }

    val queuedPackets: Int
        get() = lock.withLock { queue.size }

    val generation: Long
        get() = lock.withLock { queueGeneration }

    fun offer(value: T): OfferResult = lock.withLock {
        val size = sizeOf(value)
        if (size <= 0) return OfferResult.REJECTED_INVALID_SIZE

        if (size > byteBudget) {
            if (isKeyFrame != null) resetLocked()
            return OfferResult.REJECTED_OVERSIZED
        }

        val keyFrame = isKeyFrame?.invoke(value) == true
        if (waitingForKeyFrame && !keyFrame) return OfferResult.WAITING_FOR_KEY_FRAME

        val overCapacity = queue.size >= packetCapacity || size > byteBudget - bytes
        var result = OfferResult.ENQUEUED
        if (overCapacity && isKeyFrame != null) {
            resetLocked()
            if (!keyFrame) {
                return OfferResult.DROPPED_UNTIL_KEY_FRAME
            }
            waitingForKeyFrame = false
            result = OfferResult.RECOVERED_AT_KEY_FRAME
        } else if (overCapacity) {
            while (queue.isNotEmpty() &&
                (queue.size >= packetCapacity || size > byteBudget - bytes)) {
                bytes -= sizeOf(queue.removeFirst().value)
            }
            result = OfferResult.DROPPED_OLDEST
        }

        queue.addLast(Entry(value, queueGeneration))
        bytes += size
        if (keyFrame) waitingForKeyFrame = false
        notEmpty.signal()
        result
    }

    @Throws(InterruptedException::class)
    fun poll(timeout: Long, unit: TimeUnit): Entry<T>? {
        var remaining = unit.toNanos(timeout)
        lock.lockInterruptibly()
        try {
            while (queue.isEmpty() && remaining > 0L) {
                remaining = notEmpty.awaitNanos(remaining)
            }
            if (queue.isEmpty()) return null

            val entry = queue.removeFirst()
            bytes -= sizeOf(entry.value)
            return entry
        } finally {
            lock.unlock()
        }
    }

    /** Run decoder submission atomically with respect to reset and overflow. */
    fun runIfCurrent(entry: Entry<T>, action: () -> Unit): Boolean = lock.withLock {
        if (entry.generation != queueGeneration) return false
        action()
        true
    }

    fun isCurrent(entry: Entry<T>): Boolean = lock.withLock {
        entry.generation == queueGeneration
    }

    /** Drop queued video and require a new key frame before accepting deltas. */
    fun awaitKeyFrame() {
        lock.withLock {
            resetLocked()
        }
    }

    fun reset() {
        lock.withLock {
            resetLocked()
        }
    }

    private fun resetLocked() {
        queue.clear()
        bytes = 0
        queueGeneration++
        waitingForKeyFrame = isKeyFrame != null
    }
}
