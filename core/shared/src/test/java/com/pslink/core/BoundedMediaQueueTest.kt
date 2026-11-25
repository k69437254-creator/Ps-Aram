package com.pslink.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class BoundedMediaQueueTest {
    private data class Frame(val id: Int, val bytes: Int, val keyFrame: Boolean)

    private fun videoQueue(packetCapacity: Int = 4, byteBudget: Int = 10) =
        BoundedMediaQueue(packetCapacity, byteBudget, Frame::bytes, Frame::keyFrame)

    @Test
    fun overflowDropsDependentFramesUntilNextKeyFrame() {
        val queue = videoQueue()
        assertEquals(
            BoundedMediaQueue.OfferResult.ENQUEUED,
            queue.offer(Frame(1, 3, true))
        )
        assertEquals(
            BoundedMediaQueue.OfferResult.ENQUEUED,
            queue.offer(Frame(2, 3, false))
        )

        assertEquals(
            BoundedMediaQueue.OfferResult.DROPPED_UNTIL_KEY_FRAME,
            queue.offer(Frame(3, 5, false))
        )
        assertEquals(0, queue.queuedPackets)
        assertEquals(0, queue.queuedBytes)
        assertEquals(
            BoundedMediaQueue.OfferResult.WAITING_FOR_KEY_FRAME,
            queue.offer(Frame(4, 2, false))
        )

        assertEquals(
            BoundedMediaQueue.OfferResult.ENQUEUED,
            queue.offer(Frame(5, 4, true))
        )
        assertEquals(5, queue.poll(0, TimeUnit.MILLISECONDS)?.value?.id)
        assertEquals(null, queue.poll(0, TimeUnit.MILLISECONDS))
    }

    @Test
    fun keyFrameOverflowRetainsOnlyTheRecoveryKeyFrame() {
        val queue = videoQueue()
        queue.offer(Frame(1, 3, true))
        queue.offer(Frame(2, 3, false))
        val generation = queue.generation

        assertEquals(
            BoundedMediaQueue.OfferResult.RECOVERED_AT_KEY_FRAME,
            queue.offer(Frame(3, 8, true))
        )

        val recovered = queue.poll(0, TimeUnit.MILLISECONDS)
        assertEquals(3, recovered?.value?.id)
        assertEquals(queue.generation, recovered?.generation)
        assertTrue(queue.generation > generation)
        assertEquals(8, queue.queuedBytes)
    }

    @Test
    fun resetInvalidatesFramesAlreadyRemovedFromQueue() {
        val queue = videoQueue()
        queue.offer(Frame(1, 4, true))
        val inFlight = queue.poll(0, TimeUnit.MILLISECONDS)!!
        var submitted = false

        assertTrue(queue.isCurrent(inFlight))
        queue.reset()

        assertFalse(queue.isCurrent(inFlight))
        assertFalse(queue.runIfCurrent(inFlight) { submitted = true })
        assertFalse(submitted)
        assertEquals(0, queue.queuedBytes)
        assertEquals(0, queue.queuedPackets)
        assertEquals(
            BoundedMediaQueue.OfferResult.WAITING_FOR_KEY_FRAME,
            queue.offer(Frame(2, 1, false))
        )
    }

    @Test
    fun audioResetInvalidatesSamplesAlreadyRemovedFromQueue() {
        val queue = BoundedMediaQueue(4, 10, Frame::bytes)
        queue.offer(Frame(1, 4, false))
        val inFlight = queue.poll(0, TimeUnit.MILLISECONDS)!!

        assertTrue(queue.isCurrent(inFlight))
        queue.reset()

        assertFalse(queue.isCurrent(inFlight))
    }

    @Test
    fun audioOverflowEvictsOldestAndKeepsByteAccountingBounded() {
        val queue = BoundedMediaQueue(4, 6, Frame::bytes)
        queue.offer(Frame(1, 3, false))
        queue.offer(Frame(2, 3, false))

        assertEquals(
            BoundedMediaQueue.OfferResult.DROPPED_OLDEST,
            queue.offer(Frame(3, 4, false))
        )

        assertEquals(1, queue.queuedPackets)
        assertEquals(4, queue.queuedBytes)
        assertEquals(3, queue.poll(0, TimeUnit.MILLISECONDS)?.value?.id)
        assertEquals(0, queue.queuedBytes)
    }

    @Test
    fun byteAccountingDoesNotOverflowAtIntegerLimit() {
        val queue = BoundedMediaQueue(2, Int.MAX_VALUE, { it })
        queue.offer(Int.MAX_VALUE - 1)

        assertEquals(
            BoundedMediaQueue.OfferResult.DROPPED_OLDEST,
            queue.offer(2)
        )

        assertEquals(1, queue.queuedPackets)
        assertEquals(2, queue.queuedBytes)
    }

    @Test
    fun concurrentOffersAndResetsPreserveQueueInvariants() {
        val queue = videoQueue(packetCapacity = 8, byteBudget = 32)
        val start = CountDownLatch(1)
        val invariantsHeld = AtomicBoolean(true)
        val workers = List(4) { worker ->
            thread(start = true) {
                start.await()
                repeat(2_000) { index ->
                    if ((index + worker) % 31 == 0) {
                        queue.reset()
                    } else {
                        val keyFrame = index % 20 == 0
                        queue.offer(Frame(index, 1 + index % 8, keyFrame))
                    }
                    queue.poll(0, TimeUnit.MILLISECONDS)
                    if (queue.queuedBytes !in 0..32 || queue.queuedPackets !in 0..8) {
                        invariantsHeld.set(false)
                    }
                }
            }
        }

        start.countDown()
        workers.forEach { it.join() }

        assertTrue(invariantsHeld.get())
        assertTrue(queue.queuedBytes in 0..32)
        assertTrue(queue.queuedPackets in 0..8)
    }
}
