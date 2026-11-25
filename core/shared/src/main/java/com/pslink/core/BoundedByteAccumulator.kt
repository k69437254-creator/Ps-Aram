package com.pslink.core

import java.io.ByteArrayOutputStream

class BoundedByteAccumulator(private val maxBytes: Int) {
    private val output = ByteArrayOutputStream(minOf(maxBytes, 8 * 1024))
    private var exceeded = false
    private var size = 0

    init {
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun append(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): Boolean {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "Invalid byte range"
        }
        if (exceeded) return false
        if (length > maxBytes - size) {
            exceeded = true
            output.reset()
            size = 0
            return false
        }

        output.write(bytes, offset, length)
        size += length
        return true
    }

    fun toByteArray(): ByteArray? = if (exceeded) null else output.toByteArray()
}
