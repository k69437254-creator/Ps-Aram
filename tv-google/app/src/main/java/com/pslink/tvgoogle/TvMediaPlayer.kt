// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tvgoogle

import com.pslink.core.BoundedMediaQueue
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class TvMediaConfig(val width: Int, val height: Int, val codec: Int, val fps: Int)

/** Receives Chiaki's compressed video access units and PCM audio; no HDMI capture is involved. */
// PSLINK_LIVE_TV_PLAYOUT_V2
// Decode ahead and schedule output against the shared monotonic timeline.
class TvMediaPlayer(
    private val surfaceView: SurfaceView,
    private val onStatus: (String) -> Unit,
    private val onCaptions: (List<TvCaptionLine>) -> Unit
) : SurfaceHolder.Callback {
    companion object {
        private const val MAGIC = 0x50534C4D // PSLM
        private const val VERSION = 1
        private const val HEADER = 28
        private const val VIDEO = 1
        private const val AUDIO = 2
        private const val FLAG_KEY_FRAME = 1
        private const val VIDEO_QUEUE_CAPACITY = 96
        private const val AUDIO_QUEUE_CAPACITY = 80
        private const val MAX_VIDEO_PACKET_BYTES = 2 * 1024 * 1024
        private const val MAX_AUDIO_PACKET_BYTES = 64 * 1024
        private const val VIDEO_QUEUE_BYTE_BUDGET = 8 * 1024 * 1024
        private const val AUDIO_QUEUE_BYTE_BUDGET = 2 * 1024 * 1024
        private const val PLAYOUT_BUFFER_NS = 1_000_000_000L
    }

    private data class VideoFrame(val data: ByteArray, val ptsNs: Long, val keyFrame: Boolean)
    private data class AudioBlock(val data: ByteArray, val ptsNs: Long, val rate: Int, val channels: Int)
    private data class QueuedCaption(val frame: TvCaptionFrame, val generation: Long)

    private val running = AtomicBoolean(true)
    private val decoderEpoch = AtomicLong(0L)
    private val decoderEpochLock = Any()
    private val videoQueue = BoundedMediaQueue<VideoFrame>(
        VIDEO_QUEUE_CAPACITY,
        VIDEO_QUEUE_BYTE_BUDGET,
        { it.data.size },
        { it.keyFrame }
    )
    private val audioQueue = BoundedMediaQueue<AudioBlock>(
        AUDIO_QUEUE_CAPACITY,
        AUDIO_QUEUE_BYTE_BUDGET,
        { it.data.size }
    )
    private val captionQueue = ArrayBlockingQueue<QueuedCaption>(8)
    private val captionGeneration = AtomicLong(0L)
    @Volatile private var config: TvMediaConfig? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var senderOriginNs = 0L
    @Volatile private var localRenderOriginNs = 0L
    private var decoder: MediaCodec? = null
    private var activeConfig: TvMediaConfig? = null

    private val videoThread = Thread({ videoLoop() }, "PSLink-TV-Video-Decoder").apply { isDaemon = true }
    private val audioThread = Thread({ audioLoop() }, "PSLink-TV-Audio-Output").apply { isDaemon = true }
    private val captionThread = Thread({ captionLoop() }, "PSLink-TV-Caption-Scheduler").apply { isDaemon = true }

    init {
        surfaceView.holder.addCallback(this)
        videoThread.start()
        audioThread.start()
        captionThread.start()
    }

    fun resetStream() {
        config = null
        videoQueue.reset()
        audioQueue.reset()
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        onCaptions(emptyList())
        senderOriginNs = 0L
        localRenderOriginNs = 0L
        advanceDecoderEpoch()
    }

    fun configure(next: TvMediaConfig) {
        config = next
        videoQueue.reset()
        audioQueue.reset()
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        onCaptions(emptyList())
        senderOriginNs = 0L
        localRenderOriginNs = 0L
        advanceDecoderEpoch()
        onStatus("وصل إعداد بث اللعبة: ${next.width}×${next.height}، ${next.fps} إطار/ث")
    }

    /** Queue translated lines against the same monotonic timeline used by video and audio. */
    fun scheduleCaptions(frame: TvCaptionFrame) {
        if (!running.get() || frame.timestampNs <= 0L) return
        // Each text/clear is a timeline event. Do not cancel an earlier caption just
        // because the next OCR result arrived; both belong at different video PTS values.
        val queued = QueuedCaption(frame, captionGeneration.get())
        if (!captionQueue.offer(queued)) {
            // Under overload, invalidate the event currently waiting and retain the newest state.
            val generation = captionGeneration.incrementAndGet()
            captionQueue.clear()
            onCaptions(emptyList())
            captionQueue.offer(QueuedCaption(frame, generation))
        }
    }

    private fun awaitCaptionPlayout(queued: QueuedCaption): Boolean {
        val ptsNs = queued.frame.timestampNs
        if (ptsNs <= 0L) return false
        establishClock(ptsNs)
        val targetNs = localRenderOriginNs + (ptsNs - senderOriginNs).coerceAtLeast(0L)
        var waitNs = targetNs - System.nanoTime()
        while (waitNs > 0L && running.get() && queued.generation == captionGeneration.get()) {
            val sleepNs = minOf(waitNs, 20_000_000L)
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
            waitNs = targetNs - System.nanoTime()
        }
        return running.get() && queued.generation == captionGeneration.get()
    }

    private fun captionLoop() {
        try {
            while (running.get()) {
                val queued = captionQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (awaitCaptionPlayout(queued) && running.get()) onCaptions(queued.frame.lines)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("تعذر مزامنة الترجمة على التلفاز: ${error.localizedMessage ?: "خطأ توقيت"}")
        }
    }

    /** Called by the WebSocket receiver. Binary packets are copied once into bounded queues. */
    fun submit(packet: ByteArray) {
        if (!running.get() || packet.size < HEADER + 1) return
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != VERSION) return
        val type = input.get().toInt() and 0xff
        val flags = input.short.toInt() and 0xffff
        val ptsNs = input.long
        if (ptsNs <= 0L) return
        val arg1 = input.int
        val arg2 = input.int
        val length = input.int
        if (length <= 0 || length != input.remaining()) return
        if (type == VIDEO && length > MAX_VIDEO_PACKET_BYTES) {
            videoQueue.awaitKeyFrame()
            onStatus("إطار فيديو أكبر من الحد المسموح؛ جارٍ انتظار إطار مفتاح جديد")
            return
        }
        if (type == AUDIO && length > MAX_AUDIO_PACKET_BYTES) return
        if (type != VIDEO && type != AUDIO) return
        val payload = ByteArray(length)
        input.get(payload)
        when (type) {
            VIDEO -> {
                val keyFrame = flags and FLAG_KEY_FRAME != 0
                when (videoQueue.offer(VideoFrame(payload, ptsNs, keyFrame))) {
                    BoundedMediaQueue.OfferResult.DROPPED_UNTIL_KEY_FRAME ->
                        onStatus("امتلأ طابور الفيديو؛ جارٍ انتظار إطار مفتاح لاستعادة العرض")
                    BoundedMediaQueue.OfferResult.REJECTED_OVERSIZED ->
                        onStatus("إطار فيديو أكبر من سعة الطابور")
                    else -> Unit
                }
            }
            AUDIO -> {
                if (arg1 !in 8000..192000 || arg2 !in 1..2 || length % (arg2 * 2) != 0) return
                audioQueue.offer(AudioBlock(payload, ptsNs, arg1, arg2))
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
        videoQueue.awaitKeyFrame()
        advanceDecoderEpoch()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
        videoQueue.awaitKeyFrame()
        advanceDecoderEpoch()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
        videoQueue.awaitKeyFrame()
        advanceDecoderEpoch()
    }

    private fun advanceDecoderEpoch() {
        synchronized(decoderEpochLock) {
            decoderEpoch.incrementAndGet()
        }
    }

    private fun establishClock(ptsNs: Long) {
        synchronized(this) {
            if (senderOriginNs == 0L) {
                senderOriginNs = ptsNs
                // Hold the compressed stream before decoding so OCR/translation has time
                // to return captions for the same frame timeline.
                localRenderOriginNs = System.nanoTime() + PLAYOUT_BUFFER_NS
            }
        }
    }

    private fun awaitPlayout(
        ptsNs: Long,
        entry: BoundedMediaQueue.Entry<AudioBlock>
    ): Boolean {
        if (ptsNs <= 0L || !audioQueue.isCurrent(entry)) return false
        establishClock(ptsNs)
        val targetNs = localRenderOriginNs + (ptsNs - senderOriginNs).coerceAtLeast(0L)
        var waitNs = targetNs - System.nanoTime()
        while (waitNs > 0L && running.get() && audioQueue.isCurrent(entry)) {
            val sleepNs = minOf(waitNs, 20_000_000L)
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
            waitNs = targetNs - System.nanoTime()
        }
        return running.get() && audioQueue.isCurrent(entry)
    }

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        var observedDecoderEpoch = -1L
        try {
            while (running.get()) {
                val currentDecoderEpoch = synchronized(decoderEpochLock) {
                    decoderEpoch.get()
                }
                if (currentDecoderEpoch != observedDecoderEpoch) {
                    releaseDecoder()
                    observedDecoderEpoch = currentDecoderEpoch
                }
                val wanted = config
                val outputSurface = surface
                if (wanted == null || outputSurface == null || !outputSurface.isValid) {
                    if (outputSurface == null || !outputSurface.isValid || wanted == null) releaseDecoder()
                    Thread.sleep(8)
                    continue
                }
                if (activeConfig != wanted || decoder == null) {
                    releaseDecoder()
                    try {
                        val mime = if (wanted.codec == 0) "video/avc" else "video/hevc"
                        val created = MediaCodec.createDecoderByType(mime)
                        val format = MediaFormat.createVideoFormat(mime, wanted.width, wanted.height)
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, wanted.fps)
                        created.configure(format, outputSurface, null, 0)
                        created.start()
                        decoder = created
                        activeConfig = wanted
                        onStatus("مفكّك ترميز التلفاز جاهز؛ بانتظار إطار المفتاح")
                    } catch (error: Exception) {
                        releaseDecoder()
                        onStatus("تعذر تشغيل مفكّك فيديو التلفاز: ${error.localizedMessage ?: "Codec غير مدعوم"}")
                        Thread.sleep(1000)
                        continue
                    }
                }

                val queuedFrame = videoQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                val frame = queuedFrame.value
                val codec = decoder ?: continue
                establishClock(frame.ptsNs)
                val origin = senderOriginNs
                val relativeUs = ((frame.ptsNs - origin).coerceAtLeast(0L)) / 1000L
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null || frame.data.size > inputBuffer.capacity()) {
                        codec.queueInputBuffer(inputIndex, 0, 0, relativeUs, 0)
                        videoQueue.awaitKeyFrame()
                    } else {
                        inputBuffer.clear()
                        inputBuffer.put(frame.data)
                        val submitted = videoQueue.runIfCurrent(queuedFrame) {
                            val acceptedByCurrentDecoder = synchronized(decoderEpochLock) {
                                if (decoderEpoch.get() == currentDecoderEpoch) {
                                    codec.queueInputBuffer(inputIndex, 0, frame.data.size, relativeUs, 0)
                                    true
                                } else {
                                    false
                                }
                            }
                            if (!acceptedByCurrentDecoder) {
                                codec.queueInputBuffer(inputIndex, 0, 0, relativeUs, 0)
                            }
                        }
                        if (!submitted) {
                            codec.queueInputBuffer(inputIndex, 0, 0, relativeUs, 0)
                        }
                    }
                } else {
                    // This is a transient decoder shortage, not a stream reset.
                    Thread.sleep(2)
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex >= 0) {
                        val rendered = synchronized(decoderEpochLock) {
                            if (decoderEpoch.get() != currentDecoderEpoch) {
                                codec.releaseOutputBuffer(outputIndex, false)
                                false
                            } else {
                                val renderAtNs = localRenderOriginNs +
                                    info.presentationTimeUs.coerceAtLeast(0L) * 1000L
                                codec.releaseOutputBuffer(outputIndex, maxOf(renderAtNs, System.nanoTime()))
                                true
                            }
                        }
                        if (!rendered) break
                    } else {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("توقف عرض فيديو التلفاز: ${error.localizedMessage ?: "خطأ في فك الترميز"}")
        } finally {
            releaseDecoder()
        }
    }

    private fun audioLoop() {
        // PSLINK_AUDIO_TRACK_RECOVERY_V2
        var track: AudioTrack? = null
        var activeRate = 0
        var activeChannels = 0
        var activeQueueGeneration = audioQueue.generation

        fun discardTrack() {
            val old = track
            track = null
            activeRate = 0
            activeChannels = 0
            if (old != null) runCatching { releaseAudioTrack(old) }
        }

        try {
            while (running.get()) {
                val queuedBlock = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val block = queuedBlock.value
                if (!audioQueue.isCurrent(queuedBlock)) continue

                if (activeQueueGeneration != queuedBlock.generation) {
                    val resetSucceeded = track?.let { currentTrack ->
                        runCatching {
                            check(currentTrack.state == AudioTrack.STATE_INITIALIZED)
                            currentTrack.pause()
                            currentTrack.flush()
                            currentTrack.play()
                        }.isSuccess
                    } ?: true
                    if (!resetSucceeded) {
                        discardTrack()
                        onStatus("تعذر استعادة مسار الصوت بعد إعادة ضبط طابور الوسائط؛ ستتم تهيئته مجددًا")
                    }
                    activeQueueGeneration = queuedBlock.generation
                }

                if (track == null || activeRate != block.rate || activeChannels != block.channels) {
                    discardTrack()
                    val mask = if (block.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minimum = AudioTrack.getMinBufferSize(block.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) {
                        onStatus("تنسيق صوت اللعبة غير مدعوم على التلفاز")
                        continue
                    }

                    val created = try {
                        AudioTrack.Builder()
                            .setAudioAttributes(AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                            .setAudioFormat(AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(block.rate)
                                .setChannelMask(mask)
                                .build())
                            .setBufferSizeInBytes(max(minimum, 4096))
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .build()
                    } catch (error: Exception) {
                        onStatus("تعذر إنشاء مسار صوت التلفاز: ${error.localizedMessage ?: "AudioTrack غير متاح"}")
                        continue
                    }

                    val started = runCatching {
                        check(created.state == AudioTrack.STATE_INITIALIZED)
                        created.play()
                    }
                    if (started.isFailure) {
                        runCatching { releaseAudioTrack(created) }
                        onStatus("تعذر تهيئة أو تشغيل مسار صوت التلفاز: ${started.exceptionOrNull()?.localizedMessage ?: "AudioTrack غير مهيأ"}")
                        continue
                    }

                    track = created
                    activeRate = block.rate
                    activeChannels = block.channels
                    onStatus("صوت اللعبة متصل بالتلفاز")
                }

                if (!awaitPlayout(block.ptsNs, queuedBlock)) {
                    if (!running.get()) break
                    if (audioQueue.isCurrent(queuedBlock)) {
                        onStatus("تجاهل كتلة صوت بتوقيت غير صالح")
                    }
                    continue
                }

                var offset = 0
                while (offset < block.data.size && running.get() && audioQueue.isCurrent(queuedBlock)) {
                    val currentTrack = track ?: break
                    val chunkSize = minOf(4_096, block.data.size - offset)
                    val written = try {
                        currentTrack.write(block.data, offset, chunkSize, AudioTrack.WRITE_BLOCKING)
                    } catch (error: Exception) {
                        onStatus("تعذر كتابة صوت التلفاز: ${error.localizedMessage ?: "AudioTrack error"}؛ ستتم إعادة تهيئته")
                        discardTrack()
                        break
                    }
                    if (written < 0) {
                        val detail = if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                            "انقطع AudioTrack عن جهاز الصوت"
                        } else {
                            "أعاد AudioTrack رمز خطأ $written"
                        }
                        onStatus("$detail؛ ستتم إعادة تهيئة مسار الصوت")
                        discardTrack()
                        break
                    }
                    if (written == 0) break
                    offset += written
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("توقف صوت التلفاز: ${error.localizedMessage ?: "خطأ في تشغيل الصوت"}")
        } finally {
            discardTrack()
        }
    }

    private fun releaseAudioTrack(track: AudioTrack) {
        var failure: Exception? = null
        try {
            track.pause()
        } catch (error: Exception) {
            failure = error
        }
        try {
            track.flush()
        } catch (error: Exception) {
            if (failure == null) failure = error
        }
        try {
            track.release()
        } catch (error: Exception) {
            if (failure == null) failure = error
        }
        failure?.let {
            onStatus("تعذر تحرير مسار الصوت: ${it.localizedMessage ?: "خطأ غير معروف"}")
        }
    }

    private fun releaseDecoder() {
        val old = decoder
        decoder = null
        activeConfig = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.release() }
        }
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        surfaceView.holder.removeCallback(this)
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        videoQueue.reset()
        audioQueue.reset()
        videoThread.interrupt()
        audioThread.interrupt()
        captionThread.interrupt()
    }
}
