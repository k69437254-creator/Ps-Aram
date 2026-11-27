#!/usr/bin/env python3
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

RECEIVERS = {
    "Google TV": Path(
        "tv-google/app/src/main/java/com/pslink/tvgoogle/TvMediaPlayer.kt"
    ),
    "Fire TV": Path(
        "tv-fire/app/src/main/java/com/pslink/tvfire/TvMediaPlayer.kt"
    ),
}

START = "    private fun audioLoop() {"
END = "    private fun releaseAudioTrack(track: AudioTrack) {"


class AudioTrackRecoveryParityTests(unittest.TestCase):
    def audio_loop(self, label, path):
        full_path = ROOT / path
        self.assertTrue(full_path.is_file(), f"{label}: file missing")

        source = full_path.read_text(encoding="utf-8")
        self.assertEqual(source.count(START), 1)
        self.assertEqual(source.count(END), 1)

        begin = source.index(START)
        finish = source.index(END, begin)
        self.assertGreater(finish, begin)
        return source[begin:finish]

    def test_dead_object_recovery(self):
        for label, path in RECEIVERS.items():
            with self.subTest(receiver=label):
                body = self.audio_loop(label, path)
                self.assertIn("AudioTrack.ERROR_DEAD_OBJECT", body)
                self.assertIn("if (written < 0)", body)
                self.assertIn("currentTrack.write(", body)
                self.assertIn("discardTrack(", body)

    def test_track_state_checked_before_play(self):
        for label, path in RECEIVERS.items():
            with self.subTest(receiver=label):
                body = self.audio_loop(label, path)
                check = body.index(
                    "check(created.state == AudioTrack.STATE_INITIALIZED)"
                )
                play = body.index("created.play()")
                self.assertLess(check, play)

    def test_generation_recovery_is_guarded(self):
        for label, path in RECEIVERS.items():
            with self.subTest(receiver=label):
                body = self.audio_loop(label, path)
                self.assertIn("currentTrack.pause()", body)
                self.assertIn("currentTrack.flush()", body)
                self.assertIn("currentTrack.play()", body)
                self.assertIn("if (!resetSucceeded)", body)

    def test_cleanup_resets_format_state(self):
        for label, path in RECEIVERS.items():
            with self.subTest(receiver=label):
                body = self.audio_loop(label, path)
                self.assertIn("activeRate = 0", body)
                self.assertIn("activeChannels = 0", body)
                self.assertIn("finally", body)
                self.assertIn("discardTrack()", body)

    def test_nullable_write_is_not_used(self):
        for label, path in RECEIVERS.items():
            with self.subTest(receiver=label):
                body = self.audio_loop(label, path)
                self.assertNotIn("track?.write(", body)


if __name__ == "__main__":
    unittest.main(verbosity=2)
