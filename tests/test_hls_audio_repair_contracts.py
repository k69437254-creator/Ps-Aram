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

if __name__ == "__main__":
    unittest.main(verbosity=2)
