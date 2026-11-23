// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.AudioPlaybackCaptureConfiguration
import android.os.Build
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures only playback usages allowed by Android Playback Capture.
 * AAC output contains raw AAC-LC access units (without ADTS).
 */
class AacPlaybackCapture(
    private val projection: MediaProjection,
    private val onEncodedFrame: (ByteArray, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val BIT_RATE = 128000
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_AAC
    }

    private val running = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var encoder: MediaCodec? = null

    fun start() {
        check(Build.VERSION.SDK_INT >= 29) {
            "Audio playback capture requires Android 10 or later"
        }
        check(running.compareAndSet(false, true)) {
            "Audio capture already running"
        }

        try {
            val playbackConfig =
                AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            check(minBuffer > 0) {
                "Android returned invalid AudioRecord buffer size: $minBuffer"
            }

            val bufferSize = maxOf(minBuffer * 2, 16384)

            val audioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
            recorder = audioRecord

            check(audioRecord.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord initialization failed"
            }

            val format = MediaFormat.createAudioFormat(
                MIME,
                SAMPLE_RATE,
                CHANNELS
            ).apply {
                setInteger(
                    MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfoProfile.AAC_LC
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }

            val codec = MediaCodec.createEncoderByType(MIME)
            encoder = codec
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            codec.start()
            audioRecord.startRecording()

            worker = Thread(
                { captureLoop(audioRecord, codec, bufferSize) },
                "PSLink-AAC-Playback-Capture"
            ).apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            running.set(false)
            releaseResources()
            throw error
        }
    }

    private fun captureLoop(
        audioRecord: AudioRecord,
        codec: MediaCodec,
        bufferSize: Int
    ) {
        val pcm = ShortArray(bufferSize / 2)
        val info = MediaCodec.BufferInfo()
        var nextInputPtsUs = -1L

        try {
            while (running.get()) {
                val count = audioRecord.read(
                    pcm,
                    0,
                    pcm.size,
                    AudioRecord.READ_BLOCKING
                )

                if (count < 0) {
                    throw IllegalStateException(
                        "AudioRecord.read failed: $count"
                    )
                }

                if (count == 0) continue

                val bytes = count * 2
                val framesRead = count / CHANNELS

                if (nextInputPtsUs < 0L) {
                    // Video Surface timestamps and this audio timeline use
                    // Android's monotonic clock (System.nanoTime).
                    nextInputPtsUs = System.nanoTime() / 1000L
                }

                val chunkPtsUs = nextInputPtsUs
                nextInputPtsUs +=
                    (framesRead.toLong() * 1_000_000L) / SAMPLE_RATE

                // Retain this PCM chunk and apply bounded backpressure until
                // MediaCodec accepts it; do not silently discard captured audio.
                var inputIndex = codec.dequeueInputBuffer(10_000)
                val inputDeadlineNs = System.nanoTime() + 1_000_000_000L
                while (inputIndex < 0 && running.get()) {
                    drainEncoder(codec, info)
                    if (System.nanoTime() >= inputDeadlineNs) {
                        throw IllegalStateException("AAC encoder input remained unavailable for 1 second")
                    }
                    inputIndex = codec.dequeueInputBuffer(10_000)
                }
                if (!running.get()) break
                check(inputIndex >= 0) { "AAC encoder input remained unavailable" }
                if (inputIndex >= 0) {
                    val input = codec.getInputBuffer(inputIndex) ?: run {
                        codec.queueInputBuffer(inputIndex, 0, 0, chunkPtsUs, 0)
                        throw IllegalStateException("AAC input buffer unavailable")
                    }

                    input.clear()
                    if (input.remaining() < bytes) {
                        codec.queueInputBuffer(inputIndex, 0, 0, chunkPtsUs, 0)
                        throw IllegalStateException("AAC input buffer too small")
                    }

                    for (i in 0 until count) {
                        val value = pcm[i].toInt()
                        input.put((value and 0xFF).toByte())
                        input.put(((value ushr 8) and 0xFF).toByte())
                    }

                    codec.queueInputBuffer(
                        inputIndex,
                        0,
                        bytes,
                        chunkPtsUs,
                        0
                    )
                }

                drainEncoder(codec, info)
            }

            drainEncoder(codec, info)
        } catch (error: Exception) {
            if (running.get()) {
                onError(error.localizedMessage ?: "AAC capture failed")
            }
        } finally {
            running.set(false)
            releaseResources()
        }
    }

    private fun drainEncoder(
        codec: MediaCodec,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            when (index) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED,
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> continue
                else -> {
                    if (index < 0) {
                        throw IllegalStateException(
                            "Unexpected AAC MediaCodec output status: $index"
                        )
                    }

                    try {
                        if (
                            info.size > 0 &&
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val output = codec.getOutputBuffer(index)
                                ?: throw IllegalStateException("AAC output buffer unavailable")

                            require(info.offset >= 0 && info.size >= 0 &&
                                info.offset.toLong() + info.size <= output.capacity().toLong()) {
                                "Invalid AAC output buffer range"
                            }
                            output.position(info.offset)
                            output.limit(info.offset + info.size)

                            val bytes = ByteArray(info.size)
                            output.get(bytes)
                            onEncodedFrame(
                                bytes,
                                info.presentationTimeUs.coerceAtLeast(0L)
                            )
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    fun stop() {
        running.set(false)

        // AudioRecord.stop() unblocks a READ_BLOCKING call. The capture
        // worker remains the sole owner responsible for releasing resources.
        runCatching { recorder?.stop() }

        val activeWorker = worker
        activeWorker?.interrupt()

        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            try {
                activeWorker.join(3000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }

            if (activeWorker.isAlive) {
                onError("انتهت مهلة إيقاف التقاط AAC؛ لم تُحرر الموارد من خيط خارجي لتجنب تعارض التحرير.")
            }
        }
    }

    /**
     * Called by the capture worker's finally block, or by start() only
     * when initialization fails before the worker is launched.
     */
    private fun releaseResources() {
        val oldRecorder = recorder
        recorder = null
        runCatching { oldRecorder?.stop() }
        runCatching { oldRecorder?.release() }

        val oldEncoder = encoder
        encoder = null
        runCatching { oldEncoder?.stop() }
        runCatching { oldEncoder?.release() }

        worker = null
    }

    private object MediaCodecInfoProfile {
        const val AAC_LC = 2
    }
}
