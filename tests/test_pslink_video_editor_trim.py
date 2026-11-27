import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "mobile/src/main/java/com/pslink/mobile/capture/VideoEditorActivity.kt"

class VideoEditorRepairTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SOURCE.read_text(encoding="utf-8")

    def test_trim_uses_next_sync_frame(self):
        self.assertIn(
            "MediaExtractor.SEEK_TO_NEXT_SYNC",
            self.source
        )
        self.assertNotIn(
            "MediaExtractor.SEEK_TO_PREVIOUS_SYNC",
            self.source
        )

    def test_export_skips_frames_before_requested_start(self):
        self.assertIn(
            "extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC",
            self.source
        )
        self.assertIn("pts < startUs", self.source)
        self.assertIn("firstVideoPts = pts", self.source)

    def test_audio_before_first_video_sync_is_skipped(self):
        self.assertIn(
            "if (!isVideoTrack && pts < firstVideoPts)",
            self.source
        )

    def test_activity_does_not_delete_source_during_export(self):
        self.assertIn(
            "if (!exportRunning) deleteRawRecordingIfOwned()",
            self.source
        )
        self.assertIn(
            "if (isFinishing || isDestroyed)",
            self.source
        )
        self.assertIn(
            "deleteRawRecordingIfOwned()",
            self.source
        )

    def test_cleanup_is_limited_to_owned_cache_file(self):
        self.assertIn(
            "uri.scheme != \"file\"",
            self.source
        )
        self.assertIn(
            "cacheDir.absolutePath + File.separator",
            self.source
        )

if __name__ == "__main__":
    unittest.main()
