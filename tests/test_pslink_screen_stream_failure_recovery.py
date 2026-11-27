#!/usr/bin/env python3
"""Source-contract tests for PS Link screen-stream and HLS failure recovery."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt").read_text(encoding="utf-8")
SERVER = (ROOT / "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt").read_text(encoding="utf-8")
GATE = (ROOT / "scripts/check-source.sh").read_text(encoding="utf-8")


class ScreenStreamFailureRecoveryTests(unittest.TestCase):
    def test_video_encoder_failure_schedules_full_service_cleanup_on_main_thread(self):
        start = SERVICE.index("private fun drainEncoder(")
        end = SERVICE.index("private fun combineCodecConfig(", start)
        body = SERVICE[start:end]
        self.assertIn("Handler(Looper.getMainLooper()).post", body)
        self.assertIn("if (running.get())", body)
        self.assertIn("stopStream()", body)
        self.assertIn("report(message)", body)
        self.assertIn("PSLINK_SCREEN_STREAM_FAILURE_RECOVERY_V1", body)

    def test_hls_video_failure_disables_only_the_hls_output(self):
        start = SERVICE.index("private fun drainEncoder(")
        end = SERVICE.index("private fun combineCodecConfig(", start)
        body = SERVICE[start:end]
        self.assertIn("failHlsPublishing(", body)
        self.assertIn("hlsReorderBuffer?.offerVideo", body)
        self.assertNotIn("phoneReceiverServer?.stop()", body)

    def test_hls_failure_detaches_callbacks_and_releases_owned_resources(self):
        start = SERVICE.index("private fun failHlsPublishing(")
        end = SERVICE.index("private fun handleChiakiPcm(", start)
        body = SERVICE[start:end]
        for marker in (
            "HlsAudioRoute.FAILED",
            "hlsAudioHandler.removeCallbacks(hlsAudioFallback)",
            "chiakiPcmRegistration = null",
            "resources.registration?.close()",
            "resources.chiakiEncoder?.stop()",
            "resources.playbackCapture?.stop()",
            "resources.reorderBuffer?.close()",
            "resources.publisher?.stop()",
            "phoneReceiverServer?.notifyStreamUnavailable()",
        ):
            self.assertIn(marker, body)
        self.assertNotIn("phoneReceiverServer?.stop()", body)
        self.assertNotIn("stopStream()", body)

    def test_both_audio_callback_paths_escalate_fatal_mux_errors(self):
        start = SERVICE.index("private fun handleChiakiPcm(")
        end = SERVICE.index("private fun switchHlsAudioToFallback(", start)
        self.assertIn("failHlsPublishing(", SERVICE[start:end])
        start = SERVICE.index("private fun startPlaybackCaptureFallback(")
        end = SERVICE.index("private fun createNotificationChannel(", start)
        self.assertIn("failHlsPublishing(", SERVICE[start:end])

    def test_failed_hls_is_not_advertised_to_new_receivers(self):
        start = SERVER.index("fun notifyStreamUnavailable()")
        end = SERVER.index("fun notifyStreamAvailable()", start)
        body = SERVER[start:end]
        self.assertIn("streamAvailable.set(false)", body)
        self.assertNotIn("stop()", body)
        self.assertIn("phoneReceiverServer?.notifyStreamUnavailable()", SERVICE)

    def test_regression_test_is_in_the_existing_source_gate(self):
        self.assertIn("python3 tests/test_pslink_screen_stream_failure_recovery.py", GATE)


if __name__ == "__main__":
    unittest.main(verbosity=2)
