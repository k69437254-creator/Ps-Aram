package com.pslink.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedByteAccumulatorTest {
    @Test
    fun acceptsBodyExactlyAtLimitAcrossChunks() {
        val accumulator = BoundedByteAccumulator(64 * 1024)
        val chunk = ByteArray(16 * 1024) { it.toByte() }

        repeat(4) {
            assertTrue(accumulator.append(chunk))
        }

        assertArrayEquals(chunk + chunk + chunk + chunk, accumulator.toByteArray())
    }

    @Test
    fun rejectsAndDiscardsBodyAsSoonAsItExceedsLimit() {
        val accumulator = BoundedByteAccumulator(64 * 1024)
        val chunk = ByteArray(16 * 1024)
        repeat(4) { assertTrue(accumulator.append(chunk)) }

        assertFalse(accumulator.append(byteArrayOf(1)))
        assertNull(accumulator.toByteArray())
        assertFalse(accumulator.append(byteArrayOf(2)))
    }
}
