#!/usr/bin/env python3
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt"
CHECK_SOURCE = ROOT / "scripts/check-source.sh"


class TvAudioTrackRecoveryTests(unittest.TestCase):
    def test_audio_loop_recovers_a_dead_or_failed_track(self):
        source = PLAYER.read_text(encoding="utf-8")
        start = source.index("    private fun audioLoop()")
        end = source.index("    private fun releaseAudioTrack", start)
        body = source[start:end]
        self.assertEqual(body.count("PSLINK_AUDIO_TRACK_DEAD_OBJECT_RECOVERY_V1"), 1)
        self.assertIn("AudioTrack.ERROR_DEAD_OBJECT", body)
        self.assertIn("if (written < 0)", body)
        self.assertIn("activeTrack.write(block.data, offset, chunkSize)", body)
        self.assertIn("تعذر الكتابة إلى مسار صوت التلفاز", body)
        self.assertIn("val activeTrack = track ?: break", body)
        self.assertIn("track = null", body)
        self.assertIn("activeRate = 0", body)
        self.assertIn("activeChannels = 0", body)
        self.assertIn("releaseAudioTrack(activeTrack)", body)

    def test_audio_track_is_validated_before_playback(self):
        source = PLAYER.read_text(encoding="utf-8")
        start = source.index("    private fun audioLoop()")
        end = source.index("    private fun releaseAudioTrack", start)
        body = source[start:end]
        self.assertIn("createdTrack.state != AudioTrack.STATE_INITIALIZED", body)
        self.assertIn("createdTrack.play()", body)
        self.assertIn("resetError = runCatching", body)

    def test_source_gate_runs_this_regression(self):
        source = CHECK_SOURCE.read_text(encoding="utf-8")
        self.assertIn("PSLINK_CONSOLIDATED_REPAIR_REGRESSION_GATE_V1", source)
        self.assertIn("tests/test_pslink_audio_track_recovery.py", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
