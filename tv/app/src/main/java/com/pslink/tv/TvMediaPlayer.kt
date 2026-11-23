// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

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
        private const val PLAYOUT_BUFFER_NS = 1_000_000_000L
    }

    private data class VideoFrame(val data: ByteArray, val ptsNs: Long, val keyFrame: Boolean)
    private data class AudioBlock(val data: ByteArray, val ptsNs: Long, val rate: Int, val channels: Int)
    private data class QueuedCaption(val frame: TvCaptionFrame, val generation: Long)

    private val running = AtomicBoolean(true)
    private val videoQueue = ArrayBlockingQueue<VideoFrame>(VIDEO_QUEUE_CAPACITY)
    private val audioQueue = ArrayBlockingQueue<AudioBlock>(AUDIO_QUEUE_CAPACITY)
    private val captionQueue = ArrayBlockingQueue<QueuedCaption>(8)
    private val captionGeneration = AtomicLong(0L)
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var config: TvMediaConfig? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var awaitingKeyFrame = true
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
        videoQueue.clear()
        audioQueue.clear()
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        activeTrack?.let { track -> runCatching { track.pause(); track.flush(); track.play() } }
        onCaptions(emptyList())
        awaitingKeyFrame = true
        senderOriginNs = 0L
        localRenderOriginNs = 0L
    }

    fun configure(next: TvMediaConfig) {
        config = next
        videoQueue.clear()
        audioQueue.clear()
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        activeTrack?.let { track -> runCatching { track.pause(); track.flush(); track.play() } }
        onCaptions(emptyList())
        awaitingKeyFrame = true
        senderOriginNs = 0L
        localRenderOriginNs = 0L
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
        val payload = ByteArray(length)
        input.get(payload)
        when (type) {
            VIDEO -> {
                val keyFrame = flags and FLAG_KEY_FRAME != 0
                if (awaitingKeyFrame && !keyFrame) return
                if (!videoQueue.offer(VideoFrame(payload, ptsNs, keyFrame))) {
                    videoQueue.clear()
                    awaitingKeyFrame = true
                    if (keyFrame && videoQueue.offer(VideoFrame(payload, ptsNs, true))) awaitingKeyFrame = false
                    return
                }
                if (keyFrame) awaitingKeyFrame = false
            }
            AUDIO -> {
                if (arg1 !in 8000..192000 || arg2 !in 1..2 || length % (arg2 * 2) != 0) return
                if (!audioQueue.offer(AudioBlock(payload, ptsNs, arg1, arg2))) {
                    // Discard stale queued audio rather than playing seconds behind the game.
                    audioQueue.clear()
                    audioQueue.offer(AudioBlock(payload, ptsNs, arg1, arg2))
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
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

    private fun awaitPlayout(ptsNs: Long): Boolean {
        if (ptsNs <= 0L) return false
        establishClock(ptsNs)
        val targetNs = localRenderOriginNs + (ptsNs - senderOriginNs).coerceAtLeast(0L)
        var waitNs = targetNs - System.nanoTime()
        while (waitNs > 0L && running.get()) {
            val sleepNs = minOf(waitNs, 20_000_000L)
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
            waitNs = targetNs - System.nanoTime()
        }
        return running.get()
    }

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val wanted = config
                val outputSurface = surface
                if (wanted == null || outputSurface == null || !outputSurface.isValid) {
                    if (outputSurface == null || wanted == null) releaseDecoder()
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
                        awaitingKeyFrame = true
                        onStatus("مفكّك ترميز التلفاز جاهز؛ بانتظار إطار المفتاح")
                    } catch (error: Exception) {
                        releaseDecoder()
                        onStatus("تعذر تشغيل مفكّك فيديو التلفاز: ${error.localizedMessage ?: "Codec غير مدعوم"}")
                        Thread.sleep(1000)
                        continue
                    }
                }

                val frame = videoQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                val codec = decoder ?: continue
                if (awaitingKeyFrame && !frame.keyFrame) continue
                if (frame.keyFrame) awaitingKeyFrame = false
                establishClock(frame.ptsNs)
                val origin = senderOriginNs
                val relativeUs = ((frame.ptsNs - origin).coerceAtLeast(0L)) / 1000L
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null || frame.data.size > inputBuffer.capacity()) {
                        codec.queueInputBuffer(inputIndex, 0, 0, relativeUs, 0)
                        awaitingKeyFrame = true
                    } else {
                        inputBuffer.clear()
                        inputBuffer.put(frame.data)
                        codec.queueInputBuffer(inputIndex, 0, frame.data.size, relativeUs, 0)
                    }
                } else {
                    // Input buffer temporarily unavailable — retry next iteration.
                    // Do NOT set awaitingKeyFrame here; that would drop all frames until
                    // a key frame arrives. Just yield briefly and try again.
                    Thread.sleep(2)
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex >= 0) {
                        val renderAtNs = localRenderOriginNs + info.presentationTimeUs.coerceAtLeast(0L) * 1000L
                        codec.releaseOutputBuffer(outputIndex, maxOf(renderAtNs, System.nanoTime()))
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
        var track: AudioTrack? = null
        var activeRate = 0
        var activeChannels = 0
        try {
            while (running.get()) {
                val block = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (track == null || activeRate != block.rate || activeChannels != block.channels) {
                    track?.let { oldTrack -> runCatching { oldTrack.pause(); oldTrack.flush(); oldTrack.release() } }
                    val mask = if (block.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minimum = AudioTrack.getMinBufferSize(block.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) {
                        onStatus("تنسيق صوت اللعبة غير مدعوم على التلفاز")
                        track = null
                        continue
                    }
                    track = AudioTrack.Builder()
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
                    activeRate = block.rate
                    activeChannels = block.channels
                    activeTrack = track
                    track?.play()
                    onStatus("صوت اللعبة متصل بالتلفاز")
                }
                if (!awaitPlayout(block.ptsNs)) break
                var offset = 0
                while (offset < block.data.size && running.get()) {
                    val written = track?.write(block.data, offset, block.data.size - offset) ?: 0
                    if (written <= 0) break
                    offset += written
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("توقف صوت التلفاز: ${error.localizedMessage ?: "خطأ في تشغيل الصوت"}")
        } finally {
            track?.let { oldTrack -> runCatching { oldTrack.pause(); oldTrack.flush(); oldTrack.release() } }
            activeTrack = null
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
        activeTrack?.let { track -> runCatching { track.pause(); track.flush() } }
        videoThread.interrupt()
        audioThread.interrupt()
        captionThread.interrupt()
    }
}
