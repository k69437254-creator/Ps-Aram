// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, non-blocking PCM16 -> AAC-LC encoder. MediaCodec creation, configuration,
 * input and output all run on a dedicated worker; Chiaki's decoded-audio callback only
 * copies PCM into a bounded queue and never waits for codec startup or encoding.
 */
class ChiakiPcmAacEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val onEncodedFrame: (ByteArray, Long, Int, Int) -> Unit,
    private val onError: (String) -> Unit
) {
    private data class PcmChunk(val bytes: ByteArray, val ptsUs: Long)

    companion object {
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
        private const val BIT_RATE = 128_000
        private const val MAX_PCM_BYTES = 256 * 1024
        private val ADTS_SAMPLE_RATES = setOf(
            96000, 88200, 64000, 48000, 44100, 32000, 24000,
            22050, 16000, 12000, 11025, 8000, 7350
        )
    }

    private val running = AtomicBoolean(false)
    private val errorReported = AtomicBoolean(false)
    private val queue = ArrayBlockingQueue<PcmChunk>(24)
    @Volatile private var worker: Thread? = null
    private var lastInputPtsUs = 0L

    init {
        require(sampleRate in ADTS_SAMPLE_RATES) {
            "Unsupported AAC/ADTS Chiaki PCM sample rate: $sampleRate"
        }
        require(channels in 1..2) { "Chiaki PCM must be mono or stereo" }
    }

    @Synchronized
    fun start() {
        check(running.compareAndSet(false, true)) { "Chiaki AAC encoder is already running" }
        worker = Thread({ encodeLoop() }, "PSLink-Chiaki-AAC-Encoder").apply {
            isDaemon = true
            start()
        }
    }

    /** Non-blocking callback entry point. Returns false once the encoder rejects the stream. */
    @Synchronized
    fun offer(pcm16: ByteArray, inputRate: Int, inputChannels: Int, ptsNs: Long): Boolean {
        if (!running.get()) return false
        if (inputRate != sampleRate || inputChannels != channels) {
            fail("Chiaki PCM format changed during one HLS session (${inputRate}Hz/$inputChannels ch)")
            return false
        }
        if (pcm16.isEmpty() || pcm16.size > MAX_PCM_BYTES || ptsNs <= 0L ||
            pcm16.size % (channels * 2) != 0) {
            fail("Invalid Chiaki PCM access unit")
            return false
        }
        val ptsUs = (ptsNs / 1000L).coerceAtLeast(lastInputPtsUs)
        lastInputPtsUs = ptsUs
        if (!queue.offer(PcmChunk(pcm16.copyOf(), ptsUs))) {
            fail("Chiaki AAC encoder queue is full; switching to the permitted audio fallback")
            return false
        }
        return true
    }

    private fun encodeLoop() {
        var activeCodec: MediaCodec? = null
        val info = MediaCodec.BufferInfo()
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PCM_BYTES)
            }
            val created = MediaCodec.createEncoderByType(MIME)
            activeCodec = created
            created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            created.start()
            while (running.get() || queue.isNotEmpty()) {
                val chunk = queue.poll(25, TimeUnit.MILLISECONDS)
                if (chunk != null) queueInput(created, info, chunk)
                drainOutput(created, info)
            }
            drainOutput(created, info)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: Exception) {
            if (running.get()) fail(error.localizedMessage ?: "Chiaki AAC encoding failed")
        } finally {
            running.set(false)
            queue.clear()
            activeCodec?.let { old ->
                runCatching { old.stop() }
                runCatching { old.release() }
            }
            worker = null
        }
    }

    private fun queueInput(activeCodec: MediaCodec, info: MediaCodec.BufferInfo, chunk: PcmChunk) {
        val deadlineNs = System.nanoTime() + 1_000_000_000L
        var index = activeCodec.dequeueInputBuffer(10_000L)
        while (index < 0 && running.get()) {
            drainOutput(activeCodec, info)
            if (System.nanoTime() >= deadlineNs) {
                throw IllegalStateException("AAC encoder input buffer unavailable for one second")
            }
            index = activeCodec.dequeueInputBuffer(10_000L)
        }
        if (index < 0) return
        val input = activeCodec.getInputBuffer(index)
            ?: throw IllegalStateException("AAC encoder returned a null input buffer")
        input.clear()
        if (input.remaining() < chunk.bytes.size) {
            activeCodec.queueInputBuffer(index, 0, 0, chunk.ptsUs, 0)
            throw IllegalStateException("AAC encoder input buffer is smaller than the PCM chunk")
        }
        input.put(chunk.bytes)
        activeCodec.queueInputBuffer(index, 0, chunk.bytes.size, chunk.ptsUs, 0)
    }

    private fun drainOutput(activeCodec: MediaCodec, info: MediaCodec.BufferInfo) {
        while (true) {
            when (val index = activeCodec.dequeueOutputBuffer(info, 0L)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> {
                    if (index < 0) throw IllegalStateException("Unexpected AAC output status: $index")
                    try {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = activeCodec.getOutputBuffer(index)
                                ?: throw IllegalStateException("AAC encoder returned a null output buffer")
                            require(info.offset >= 0 && info.size >= 0 &&
                                info.offset.toLong() + info.size <= output.capacity().toLong()) {
                                "Invalid AAC output buffer range"
                            }
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            onEncodedFrame(bytes, info.presentationTimeUs.coerceAtLeast(0L), sampleRate, channels)
                        }
                    } finally {
                        activeCodec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun fail(message: String) {
        if (errorReported.compareAndSet(false, true)) {
            running.set(false)
            onError(message)
        }
    }

    fun stop() {
        running.set(false)
        val activeWorker = worker
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            try {
                activeWorker.join(4_000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (activeWorker.isAlive) fail("Timed out while stopping Chiaki AAC encoder")
        }
    }
}
