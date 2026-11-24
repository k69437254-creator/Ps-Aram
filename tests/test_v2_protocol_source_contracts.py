#!/usr/bin/env python3
"""Static source-contract checks for v2 heartbeat handling and Arabic OCR assets."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class V2ProtocolSourceContracts(unittest.TestCase):
    def test_sources_define_authenticated_v2_heartbeat_handling(self):
        protocol = (
            ROOT / "core/shared/src/main/java/com/pslink/core/PsLinkProtocol.kt"
        ).read_text(encoding="utf-8")
        self.assertIn('const val VERSION = 2', protocol)
        self.assertIn('const val KIND_HEARTBEAT = "heartbeat"', protocol)
        self.assertIn('const val KIND_HEARTBEAT_ACK = "heartbeat_ack"', protocol)
        server_paths = (
            "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
            "tv-google/app/src/main/java/com/pslink/tvgoogle/TvSubtitleServer.kt",
            "tv-fire/app/src/main/java/com/pslink/tvfire/TvSubtitleServer.kt",
            "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt",
        )
        for path in server_paths:
            with self.subTest(server=path):
                server = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("message?.optInt(\"v\", -1) == PsLinkProtocol.VERSION", server)
                self.assertIn("message.optString(\"kind\") == PsLinkProtocol.KIND_HEARTBEAT", server)
                self.assertIn("PsLinkProtocol.KIND_HEARTBEAT_ACK", server)

    def test_arabic_ocr_model_is_bundled_and_loaded_from_app_assets(self):
        model = ROOT / "mobile/src/main/assets/tessdata/ara.traineddata"
        translator = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
        ).read_text(encoding="utf-8")

        self.assertTrue(model.is_file(), f"Missing bundled OCR model: {model}")
        self.assertGreater(model.stat().st_size, 1_000_000)
        self.assertIn('assets.open("tessdata/ara.traineddata")', translator)
        self.assertIn('activity.cacheDir.absolutePath + File.separator', translator)
        self.assertIn("TessBaseAPI.OEM_DEFAULT", translator)
        self.assertIn("val initialized = tessBaseAPI.init(", translator)
        self.assertIn("if (!initialized)", translator)


if __name__ == "__main__":
    unittest.main(verbosity=2)
