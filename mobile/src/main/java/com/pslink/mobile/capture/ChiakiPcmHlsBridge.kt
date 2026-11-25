// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** Delivers decoded Chiaki PCM to the active phone HLS publisher, without coupling Chiaki to HLS. */
object ChiakiPcmHlsBridge {
    private data class Registration(
        val id: Long,
        val listener: (ByteArray, Int, Int, Long) -> Unit
    )

    private val ids = AtomicLong(0L)
    private val lock = Any()
    private var active: Registration? = null

    fun attach(listener: (ByteArray, Int, Int, Long) -> Unit): Closeable {
        val registration = Registration(ids.incrementAndGet(), listener)
        synchronized(lock) { active = registration }
        return Closeable {
            synchronized(lock) {
                if (active?.id == registration.id) active = null
            }
        }
    }

    @JvmStatic
    fun publish(pcm16: ByteArray, sampleRate: Int, channels: Int, ptsNs: Long) {
        if (pcm16.isEmpty() || pcm16.size > MAX_PCM_BYTES || ptsNs <= 0L) return
        if (sampleRate !in ADTS_SAMPLE_RATES || channels !in 1..2) return
        if (pcm16.size % (channels * 2) != 0) return
        val listener = synchronized(lock) { active?.listener } ?: return
        // The encoder copies again into its bounded queue. Copy here so native callback
        // storage can never be retained or changed after this method returns.
        listener(pcm16.copyOf(), sampleRate, channels, ptsNs)
    }

    private val ADTS_SAMPLE_RATES = setOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350
    )
    private const val MAX_PCM_BYTES = 256 * 1024
}
