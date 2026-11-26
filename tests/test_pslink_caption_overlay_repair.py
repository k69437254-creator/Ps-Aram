#!/usr/bin/env python3
import runpy
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
OVERLAY = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
ENSURE = OVERLAY["ensure_caption_clear_paths"]
class CaptionOverlayRepairTests(unittest.TestCase):
    def test_current_translator_is_preserved_and_idempotent(self):
        source = (ROOT / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt").read_text(encoding="utf-8")
        self.assertEqual(source, ENSURE(source))
        self.assertEqual(source, ENSURE(ENSURE(source)))
    def test_old_stop_and_pause_are_upgraded_for_both_receiver_paths(self):
        source = (
            "class Translator {\n"
            "    fun stop() {\n        enabled = false\n    }\n"
            "    fun pause() {\n        loop?.cancel()\n    }\n"
            "    fun clearOcr() {\n        lastSource = \"\"\n        lastTranslation = \"\"\n        onCaption(emptyList())\n        tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)\n    }\n"
            "}\n"
        )
        fixed = ENSURE(source)
        self.assertIn("tvClient?.sendCaptions(emptyList(), clearPtsNs, sessionId)", fixed)
        self.assertIn("PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), clearPtsNs)", fixed)
        self.assertEqual(fixed, ENSURE(fixed))
    def test_current_timestamped_paths_are_not_duplicated(self):
        source = (
            "class Translator {\n"
            "    fun stop() {\n        val clearPtsNs = System.nanoTime()\n        tvClient?.sendCaptions(emptyList(), clearPtsNs, sessionId)\n        PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), clearPtsNs)\n        enabled = false\n    }\n"
            "    fun pause() {\n        val clearPtsNs = System.nanoTime()\n        tvClient?.sendCaptions(emptyList(), clearPtsNs, sessionId)\n        PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), clearPtsNs)\n        loop?.cancel()\n    }\n"
            "    fun clearOcr() {\n        lastSource = \"\"\n        lastTranslation = \"\"\n        onCaption(emptyList())\n        tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)\n    }\n"
            "}\n"
        )
        self.assertEqual(source, ENSURE(source))

    def test_existing_receiver_clear_reuses_shared_timestamp(self):
        source = (
            "class Translator {\n"
            "    fun stop() {\n"
            "        val clearPtsNs = System.nanoTime()\n"
            "        tvClient?.sendCaptions(emptyList(), clearPtsNs, sessionId)\n"
            "        PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), samplePtsNs)\n"
            "        enabled = false\n"
            "    }\n"
            "    fun pause() {\n"
            "        val clearPtsNs = System.nanoTime()\n"
            "        tvClient?.sendCaptions(emptyList(), clearPtsNs, sessionId)\n"
            "        PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), clearPtsNs)\n"
            "        loop?.cancel()\n"
            "    }\n"
            "    fun clearOcr() {\n"
            "        lastSource = \"\"\n"
            "        lastTranslation = \"\"\n"
            "        onCaption(emptyList())\n"
            "        tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)\n"
            "    }\n"
            "}\n"
        )
        fixed = ENSURE(source)
        stop_body = fixed.split("fun stop()", 1)[1].split("fun pause()", 1)[0]
        self.assertEqual(stop_body.count("PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), clearPtsNs)"), 1)
        self.assertNotIn("publishCaptionsToReceivers(emptyList(), samplePtsNs)", stop_body)
        self.assertEqual(fixed, ENSURE(fixed))


    def test_legacy_tv_clear_reuses_existing_receiver_call(self):
        source = (
            "class Translator {\n"
            "    fun stop() {\n"
            "        tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)\n"
            "        PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), samplePtsNs)\n"
            "        enabled = false\n"
            "    }\n"
            "    fun pause() {\n"
            "        tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)\n"
            "        loop?.cancel()\n"
            "    }\n"
            "    fun clearOcr() {\n"
            "        lastSource = \"\"\n"
            "        lastTranslation = \"\"\n"
            "        onCaption(emptyList())\n"
            "        tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)\n"
            "    }\n"
            "}\n"
        )
        fixed = ENSURE(source)
        stop_body = fixed.split("fun stop()", 1)[1].split("fun pause()", 1)[0]
        self.assertEqual(
            stop_body.count("PhoneReceiverServer.publishCaptionsToReceivers(emptyList(), clearPtsNs)"),
            1,
        )
        self.assertNotIn("publishCaptionsToReceivers(emptyList(), samplePtsNs)", stop_body)
        self.assertEqual(fixed, ENSURE(fixed))

if __name__ == "__main__":
    unittest.main()
