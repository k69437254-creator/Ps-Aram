#!/usr/bin/env python3
import runpy
import unittest
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


class NativeRecorderCleanupTests(unittest.TestCase):
    def test_native_worker_stops_producers_then_frees_and_resets_the_queue(self):
        worker = runpy.run_path(str(ROOT / "scripts/prepare_pslink_audio_recording.py"))["WORKER"]
        marker = "/* PSLINK_RECORDER_QUEUE_DRAIN_ON_FAILURE_V1 */"
        self.assertEqual(worker.count(marker), 1)
        lock = worker.rfind("pthread_mutex_lock(&decoder->recording_mutex);", 0,
                            worker.index("decoder->recording_muxer_started = muxer_finalized;"))
        unlock = worker.index("pthread_mutex_unlock(&decoder->recording_mutex);", lock)
        disable = worker.index("decoder->recording_requested = false;", lock)
        free_buffer = worker.index("free(decoder->recording_queue[i].data);", lock)
        self.assertLess(lock, disable)
        self.assertLess(disable, free_buffer)
        self.assertLess(free_buffer, unlock)
        for token in (
            "decoder->recording_queue[i].data = NULL;",
            "decoder->recording_queue_head = 0;",
            "decoder->recording_queue_tail = 0;",
            "decoder->recording_queue_count = 0;",
            "decoder->recording_queue[i].is_audio = false;",
        ):
            self.assertIn(token, worker)

    def test_screen_recording_failure_deletes_the_partial_file(self):
        source = (ROOT / "mobile/src/main/java/com/pslink/mobile/capture/ScreenRecordingService.kt").read_text(encoding="utf-8")
        start = source.index("    private fun fail(message: String)")
        end = source.index("    private fun createNotificationChannel()", start)
        body = source[start:end]
        self.assertIn("outputFile?.delete()", body)
        self.assertIn("outputFile = null", body)
        self.assertLess(body.index("recorder = null"), body.index("outputFile?.delete()"))

    def test_video_editor_handles_rotation_without_recreating_and_deleting_source(self):
        manifest = ET.parse(ROOT / "mobile/src/main/AndroidManifest.xml").getroot()
        ns = "{http://schemas.android.com/apk/res/android}"
        activity = next(node for node in manifest.iter("activity")
                        if node.get(ns + "name") == "com.pslink.mobile.capture.VideoEditorActivity")
        config_changes = set(activity.get(ns + "configChanges", "").split("|"))
        self.assertTrue({"orientation", "screenSize", "keyboardHidden"}.issubset(config_changes))


if __name__ == "__main__":
    unittest.main(verbosity=2)
