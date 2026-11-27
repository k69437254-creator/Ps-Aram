#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

class HlsAudioRepairContracts(unittest.TestCase):
    def read(self, relative):
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_bootstrap_overlay_forwards_native_pcm_to_hls_bridge(self):
        overlay = self.read("scripts/prepare_pslink_live_tv_streaming.py")
        self.assertIn("ChiakiPcmHlsBridge.publish(data, sampleRate, channels, ptsNs)", overlay)
        self.assertIn('elif "ChiakiPcmHlsBridge.publish" not in src:', overlay)

    def test_stream_activity_has_chiaki_pcm_hls_bridge_when_bootstrapped(self):
        stream_path = ROOT / "third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
        if stream_path.is_file():
            stream = stream_path.read_text(encoding="utf-8")
            self.assertIn("ChiakiPcmHlsBridge.publish(data, sampleRate, channels, ptsNs)", stream)
            self.assertIn("private var psLinkRecordingActive", stream)

    def test_service_has_native_first_single_source_fallback(self):
        service = self.read("mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt")
        for token in ("HlsAudioRoute.WAITING", "HlsAudioRoute.CHIAKI_PCM", "HlsAudioRoute.PLAYBACK_CAPTURE",
                      "ChiakiPcmHlsBridge.attach", "HLS_NATIVE_AUDIO_WAIT_MS",
                      "hlsAudioHandler.removeCallbacks(hlsAudioFallback)", "chiakiPcmRegistration?.close()"):
            self.assertIn(token, service)

    def test_all_hls_access_units_share_bounded_pts_reorder_buffer(self):
        service = self.read("mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt")
        for token in ("hlsReorderBuffer?.offerVideo", "reorder.offerAudio(frame, ptsUs, 48000, 2)",
                      "hlsReorderBuffer?.offerAudio(frame, ptsUs, outputRate, outputChannels)"):
            self.assertIn(token, service)
        reorder = self.read("mobile/src/main/java/com/pslink/mobile/capture/HlsAccessUnitReorderBuffer.kt")
        for token in ("PriorityQueue<Entry>", "reorderWindowUs", "lateDropCount", "capacity",
                      "maxQueuedBytes", "maxAccessUnitBytes", "pendingBytes", "oversizedDropCount"):
            self.assertIn(token, reorder)
        tests = self.read("mobile/src/test/java/com/pslink/mobile/capture/HlsAccessUnitReorderBufferTest.kt")
        self.assertIn("enforcesBothAccessUnitAndAggregateByteCaps", tests)

    def test_aac_encoder_is_bounded_and_reports_overflow(self):
        encoder = self.read("mobile/src/main/java/com/pslink/mobile/capture/ChiakiPcmAacEncoder.kt")
        self.assertIn("ArrayBlockingQueue<PcmChunk>(24)", encoder)
        self.assertIn("MediaCodec.createEncoderByType", encoder)
        self.assertIn("queue.offer(PcmChunk(pcm16.copyOf(), ptsUs))", encoder)
        self.assertIn("PCM format changed", encoder)
        self.assertIn("BUFFER_FLAG_CODEC_CONFIG", encoder)

    def test_adts_uses_actual_aac_sample_rate_and_channel_count(self):
        publisher = self.read("mobile/src/main/java/com/pslink/mobile/capture/HlsTransportStreamPublisher.kt")
        self.assertIn("sampleRate: Int = 48000", publisher)
        self.assertIn("frequencyIndex = when (sampleRate)", publisher)
        self.assertIn("require(channels in 1..2)", publisher)

    def test_ocr_timestamp_is_taken_after_pixelcopy_and_sampling_is_faster(self):
        translator = self.read("mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt")
        self.assertIn("SAMPLE_INTERVAL_MS = 350L", translator)
        self.assertLess(translator.index("val bitmap = captureFrame()"), translator.index("val samplePtsNs = System.nanoTime()"))

    def test_contracts_are_in_android_ci_source_gate(self):
        gate = self.read("scripts/check-source.sh")
        self.assertIn("tests/test_hls_audio_repair_contracts.py", gate)

    def test_hls_playlist_ready_callback_uses_named_argument(self):
        service = self.read("mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt")
        self.assertRegex(
            service,
            r"HlsTransportStreamPublisher\(\s*hlsDirectory,\s*onPlaylistReady\s*=\s*\{\s*receiver\.notifyStreamAvailable\(\)\s*\}\s*\)"
        )
        self.assertNotRegex(
            service,
            r"HlsTransportStreamPublisher\(\s*hlsDirectory\s*\)\s*\{"
        )

    def test_hls_playlist_publish_is_atomic(self):
        publisher = self.read("mobile/src/main/java/com/pslink/mobile/capture/HlsTransportStreamPublisher.kt")
        self.assertIn("playlistRenamer: (File, File) -> Unit = { source, destination ->", publisher)
        self.assertIn("Os.rename(source.absolutePath, destination.absolutePath)", publisher)
        self.assertIn("playlistRenamer(temporary, destination)", publisher)
        self.assertIn("catch (error: Exception)", publisher)
        self.assertIn("temporary.delete()", publisher)
        self.assertNotIn("destination.delete()", publisher)

        start = publisher.split("@Synchronized fun start()", 1)[1].split(
            "/** Access unit", 1
        )[0]
        self.assertNotIn('"index.m3u8"', start)
        self.assertNotIn('file.name == "index.m3u8"', start)

        close = publisher.split("private fun closeSegment()", 1)[1].split(
            "private fun writePlaylist", 1
        )[0]
        self.assertLess(
            close.index("writePlaylist(endList = false)"),
            close.index("it.name !in retainedNames")
        )

    def test_phone_receiver_uses_explicit_https_public_base_url(self):
        phone = self.read("mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt")
        build = self.read("mobile/build.gradle.kts")
        service = self.read("mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt")

        self.assertIn("normalizePublicBaseUrl(publicBaseUrl)", phone)
        self.assertIn('parsed.scheme.equals("https", ignoreCase = true)', phone)
        self.assertIn("configuredPublicBaseUrl", phone)
        self.assertIn("localIpv4Address()", phone)
        self.assertIn('BuildConfig.PSLINK_PUBLIC_BASE_URL', service)
        self.assertIn('providers.gradleProperty("pslink.publicBaseUrl")', build)
        self.assertIn('buildConfigField("String", "PSLINK_PUBLIC_BASE_URL"', build)

        for token in ("X-Forwarded-Proto", "X-Forwarded-Host", "X-Forwarded-Port",
                      "advertisedBaseUrl(call)", "val advertisedBaseUrl: String?"):
            self.assertNotIn(token, phone)

    def test_arabic_ocr_uses_actual_tesseract_line_bounds(self):
        translator = self.read("mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt")
        for token in (
            "getResultIterator()",
            "TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE",
            "getBoundingRect",
            "ArabicOcrLine",
        ):
            self.assertIn(token, translator)
        self.assertNotIn("left = 0.05f", translator)

    def test_translation_activity_only_releases_owned_foreground_stream(self):
        activity = self.read("mobile/src/main/java/com/pslink/mobile/TranslationActivity.kt")
        service = self.read("mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt")
        self.assertIn("TvScreenStreamService.activityOwnsService = true", activity)
        self.assertIn("TvScreenStreamService.activityOwnsService", activity)
        self.assertIn("!isChangingConfigurations && isFinishing", activity)
        self.assertIn("ACTION_RELEASE_CLIENT", activity)
        self.assertIn("activityOwnsService = false", service)

    def test_stream_recording_overlay_keeps_recording_state_on_rerun(self):
        overlay = self.read("scripts/prepare_pslink_stream_recording.py")
        self.assertIn('if "private var psLinkRecordingActive" not in src', overlay)
        self.assertNotIn(
            'src = re.sub(r"(?m)^\\s*private var psLinkRecordingActive',
            overlay,
        )

    def test_live_overlay_does_not_overwrite_newer_generated_files(self):
        overlay = self.read("scripts/prepare_pslink_live_tv_streaming.py")
        self.assertIn("def write_if_incomplete", overlay)
        self.assertNotIn("write(path, TV_CLIENT)", overlay)
        self.assertIn('("MEDIA_AUDIO_PCM16", "fun sendVideoSample"', overlay)

if __name__ == "__main__":
    unittest.main(verbosity=2)
