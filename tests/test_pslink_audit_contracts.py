#!/usr/bin/env python3
import json, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
EXPECTED={"android-mobile","android-tv","google-tv","fire-tv","tizen","webos","roku","xbox-edge"}
class Contracts(unittest.TestCase):
    def test_exact_eight_targets(self):
        data=json.loads((ROOT/"platforms/targets.json").read_text(encoding="utf-8"))
        ids=[x["id"] for x in data["targets"]]
        self.assertEqual(set(ids),EXPECTED)
        self.assertEqual(len(ids),8)
    def test_core_platform_sources_exist(self):
        for rel in [
          "mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt",
          "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt",
          "receivers/tizen/media-player.js","receivers/webos/media-player.js",
          "receivers/roku/source/MainScene.brs","receivers/xbox-edge/app.js"]:
            with self.subTest(path=rel): self.assertTrue((ROOT/rel).is_file())
    def test_roku_validators_are_hls_scoped(self):
        for rel in ["receivers/roku/source/MainScene.brs","receivers/roku/source/components/PairingTask.brs"]:
            s=(ROOT/rel).read_text(encoding="utf-8")
            # Relaxed HLS validation: accept any .m3u8/.m3u playlist path (not just /hls/index.m3u8)
            for token in ['Right(lowerPath, 5) <> ".m3u8"','Right(lowerPath, 4) <> ".m3u"','Instr(1, url, "@") > 0','Instr(1, url, "?") > 0','Instr(1, url, "#") > 0']:
                self.assertIn(token,s)


    def test_tvsubtitleclient_uses_media_state_lock(self):
        s = (ROOT / "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt").read_text(encoding="utf-8")
        self.assertIn("private val mediaStateLock = Any()", s)
        self.assertIn("synchronized(mediaStateLock)", s)

    def test_tvmediaplayer_video_loop_no_false_awaiting_keyframe(self):
        s = (ROOT / "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt").read_text(encoding="utf-8")
        # When dequeueInputBuffer fails, we should NOT set awaitingKeyFrame = true
        self.assertNotIn("awaitingKeyFrame = true\n                }", s.replace("Thread.sleep(2)", "XXX"))

    def test_livegametranslator_arabic_ocr_support(self):
        s = (ROOT / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt").read_text(encoding="utf-8")
        self.assertIn("Script.ARABIC", s)
        self.assertIn("initTessBaseAPI", s)
        self.assertIn("ocrArabic", s)
        self.assertIn("closeTessBaseAPI", s)
        self.assertIn("TessBaseAPI", s)

    def test_roku_pairing_uses_websocket_not_http_post(self):
        s = (ROOT / "receivers/roku/source/components/PairingTask.brs").read_text(encoding="utf-8")
        self.assertNotIn("SetRequest(\"POST\")", s)
        self.assertIn("roWebSocket", s)
        self.assertIn("ws://", s)

    def test_web_js_files_have_heartbeat(self):
        for rel in ["receivers/tizen/pslink-client.js","receivers/webos/pslink-client.js","receivers/xbox-edge/pslink-client.js"]:
            s = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIn("_startHeartbeat", s)
            self.assertIn("_stopHeartbeat", s)
            self.assertIn("getConnectionState", s)
            self.assertIn("isReady", s)

if __name__=="__main__": unittest.main(verbosity=2)
