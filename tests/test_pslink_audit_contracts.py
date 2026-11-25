#!/usr/bin/env python3
import json, runpy, unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
EXPECTED={
    "android-mobile","android-tv","google-tv","fire-tv","tizen",
    "webos","roku","web","xbox-edge","playstation",
}
class Contracts(unittest.TestCase):
    def test_live_overlay_adds_ocr_timestamp_idempotently_at_any_indent(self):
        overlay = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
        ensure_timestamp = overlay["ensure_sample_timestamp"]
        source = (
            "                        val sampleStartedAt = SystemClock.elapsedRealtime()\n"
            "                        val bitmap = captureFrame()\n"
        )
        expected = (
            "                        val sampleStartedAt = SystemClock.elapsedRealtime()\n"
            "                        val samplePtsNs = System.nanoTime()\n"
            "                        val bitmap = captureFrame()\n"
        )
        self.assertEqual(expected, ensure_timestamp(source))
        self.assertEqual(expected, ensure_timestamp(expected))

    def test_live_overlay_rejects_ambiguous_ocr_sample_timestamps(self):
        overlay = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
        source = (
            "val samplePtsNs = System.nanoTime()\n"
            "val samplePtsNs = System.nanoTime()\n"
        )
        with self.assertRaisesRegex(SystemExit, "found 2"):
            overlay["ensure_sample_timestamp"](source)

    def test_live_overlay_rejects_ambiguous_timestamp_anchors(self):
        overlay = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
        source = (
            "val sampleStartedAt = SystemClock.elapsedRealtime()\n"
            "val bitmap = captureFrame()\n"
            "val sampleStartedAt = SystemClock.elapsedRealtime()\n"
            "val bitmap = captureFrame()\n"
        )
        with self.assertRaisesRegex(SystemExit, "found 2"):
            overlay["ensure_sample_timestamp"](source)

    def test_caption_geometry_signature_is_precise_and_indent_independent(self):
        overlay = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
        ensure_signature = overlay["ensure_caption_geometry_signature"]
        source = (
            "        val signature = detected.joinToString(\"|\") {\n"
            "            \"${it.source}@${(it.left * 100).toInt()},${(it.top * 100).toInt()}\"\n"
            "        }\n"
        )
        expected = (
            "        val signature = detected.joinToString(\"|\") {\n"
            "            \"${it.source}@${(it.left * 1000).toInt()},"
            "${(it.top * 1000).toInt()},${(it.right * 1000).toInt()},"
            "${(it.bottom * 1000).toInt()}\"\n"
            "        }\n"
        )
        self.assertEqual(expected, ensure_signature(source))
        self.assertEqual(expected, ensure_signature(expected))

    def test_live_overlay_accepts_existing_session_scoped_caption_clears(self):
        overlay = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
        source = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
        ).read_text(encoding="utf-8")
        ensure_clears = overlay["ensure_caption_clear_paths"]
        self.assertEqual(source, ensure_clears(source))
        self.assertEqual(source, ensure_clears(ensure_clears(source)))

    def test_caption_geometry_overlay_is_idempotent_on_current_translator(self):
        overlay = runpy.run_path(str(ROOT / "scripts/prepare_pslink_live_tv_streaming.py"))
        source = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
        ).read_text(encoding="utf-8")
        ensure_signature = overlay["ensure_caption_geometry_signature"]
        self.assertEqual(source, ensure_signature(source))
        self.assertEqual(source, ensure_signature(ensure_signature(source)))

    def test_exact_ten_targets(self):
        data=json.loads((ROOT/"platforms/targets.json").read_text(encoding="utf-8"))
        ids=[x["id"] for x in data["targets"]]
        self.assertEqual(set(ids),EXPECTED)
        self.assertEqual(len(ids),10)

    def test_acceptance_matrix_has_every_target_and_gate(self):
        data=json.loads((ROOT/"platforms/targets.json").read_text(encoding="utf-8"))
        matrix=data["acceptanceMatrix"]
        self.assertEqual(set(matrix),EXPECTED)
        criteria=set(data["acceptanceCriteria"])
        self.assertEqual(len(criteria),13)
        for target, result in matrix.items():
            with self.subTest(target=target):
                self.assertEqual(set(result),criteria)
                self.assertTrue(all(isinstance(value,str) and value for value in result.values()))
    def test_core_platform_sources_exist(self):
        for rel in [
          "mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt",
          "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt",
          "receivers/tizen/media-player.js","receivers/webos/media-player.js",
          "receivers/roku/source/MainScene.brs","receivers/xbox-edge/app.js"]:
            with self.subTest(path=rel): self.assertTrue((ROOT/rel).is_file())

    def test_google_and_fire_tv_activities_use_their_module_types(self):
        for module, package in (("tv-google", "tvgoogle"), ("tv-fire", "tvfire")):
            activity = (
                ROOT / module / "app/src/main/java/com/pslink" / package / "TvReceiverActivity.kt"
            ).read_text(encoding="utf-8")
            source_dir = ROOT / module / "app/src/main/java/com/pslink" / package
            self.assertIn(f"package com.pslink.{package}", activity)
            self.assertNotIn("import com.pslink.tv.", activity)
            layout = (ROOT / module / "app/src/main/res/layout/caption_overlay.xml").read_text(
                encoding="utf-8"
            )
            self.assertIn(f"<com.pslink.{package}.TvCaptionOverlayView", layout)
            for class_name in ("TvCaptionOverlayView", "TvMediaPlayer", "TvSubtitleServer"):
                with self.subTest(module=module, class_name=class_name):
                    source = (source_dir / f"{class_name}.kt").read_text(encoding="utf-8")
                    self.assertIn(f"class {class_name}", source)

    def test_roku_validators_are_hls_scoped(self):
        for rel in ["receivers/roku/source/MainScene.brs","receivers/roku/source/components/PairingTask.brs"]:
            s=(ROOT/rel).read_text(encoding="utf-8")
            # Relaxed HLS validation: accept any .m3u8/.m3u playlist path (not just /hls/index.m3u8)
            for token in ['Right(lowerPath, 5) <> ".m3u8"','Right(lowerPath, 4) <> ".m3u"','Instr(1, url, "@") > 0','Instr(1, url, "?") > 0','Instr(1, url, "#") > 0']:
                self.assertIn(token,s)


    def test_tvsubtitleclient_uses_media_state_lock(self):
        s = (ROOT / "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt").read_text(encoding="utf-8")
        self.assertIn("private val mediaStateLock = java.lang.Object()", s)
        self.assertIn("mediaStateLock.wait()", s)
        self.assertIn("mediaStateLock.notifyAll()", s)
        dequeue = s[s.index("private fun takeMediaPacket"):s.index("private fun resetMediaQueueLocked")]
        self.assertIn("synchronized(mediaStateLock)", dequeue)
        self.assertIn("mediaQueue.poll()", dequeue)
        enqueue = s[s.index("private fun enqueueMediaPacket"):s.index("private fun cancelHandshakeTimeoutLocked")]
        self.assertIn("synchronized(mediaStateLock)", enqueue)
        self.assertIn("mediaQueue.offer", enqueue)
        self.assertIn("awaitingKeyFrame && !keyFrame", enqueue)
        sender = s[s.index("fun sendVideoSample"):s.index("fun sendAudioSample")]
        self.assertNotIn("val dropFrame", sender)

    def test_tvmediaplayer_video_loop_no_false_awaiting_keyframe(self):
        s = (ROOT / "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt").read_text(encoding="utf-8")
        # When dequeueInputBuffer fails, we should NOT set awaitingKeyFrame = true
        self.assertNotIn("awaitingKeyFrame = true\n                }", s.replace("Thread.sleep(2)", "XXX"))

    def test_livegametranslator_arabic_ocr_support(self):
        s = (ROOT / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt").read_text(encoding="utf-8")
        self.assertIn("Script.ARABIC", s)
        self.assertIn("initTessBaseAPI", s)
        self.assertIn("ocrArabic", s)
        cleanup = s[s.index("} finally {", s.index("private fun beginLoop")):s.index("if (cleanupError != null)")]
        self.assertIn("tessBaseApi?.end()", cleanup)
        initialization = s[s.index("private fun initTessBaseAPI"):s.index("private fun ocrArabic")]
        self.assertIn("api?.end()", initialization)
        self.assertIn("TessBaseAPI", s)
        self.assertIn("StableTextGate(requiredMatches = 2)", s)
        self.assertIn("stableTextGate.offer(signature)", s)
        model = ROOT / "mobile/src/main/assets/tessdata/ara.traineddata"
        self.assertTrue(model.is_file())
        self.assertGreater(model.stat().st_size, 1_000_000)

    def test_roku_pairing_uses_websocket_not_http_post(self):
        s = (ROOT / "receivers/roku/source/components/PairingTask.brs").read_text(encoding="utf-8")
        self.assertNotIn("SetRequest(\"POST\")", s)
        self.assertNotIn('ws.SetUrl("ws://" + host + ":8765/subtitle")', s)
        for token in (
            'CreateObject("roWebSocket")',
            'wsUrl = "wss://" + authority + "/subtitle"',
            'wsUrl = "ws://" + host + ":8765/subtitle"',
            'ws.SetCertificatesFile("common:/certs/ca-bundle.crt")',
            "ws.SetUrl(wsUrl)",
            "ws.Open(10000)",
            "ws.Send(authMsg)",
            'type(msg) = "roWebSocketEvent"',
            "msg.GetInfo()",
            "info.Text",
        ):
            with self.subTest(token=token):
                self.assertIn(token, s)
        for obsolete in ("ws.connect(", "ws.sendText(", "getConnectionState()"):
            self.assertNotIn(obsolete, s)

    def test_web_js_files_have_heartbeat(self):
        for rel in [
            "receivers/web/pslink-client.js",
            "receivers/tizen/pslink-client.js",
            "receivers/webos/pslink-client.js",
            "receivers/xbox-edge/pslink-client.js",
        ]:
            s = (ROOT / rel).read_text(encoding="utf-8")
            self.assertIn("_startHeartbeat", s)
            self.assertIn("_stopHeartbeat", s)
            self.assertIn("getConnectionState", s)
            self.assertIn("isReady", s)

if __name__=="__main__": unittest.main(verbosity=2)
