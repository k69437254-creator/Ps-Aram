#!/usr/bin/env python3
"""Static source-contract checks for v2 heartbeat handling and Arabic OCR assets."""

import re
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
        self.assertIn('val sessionId: String = ""', protocol)
        self.assertIn('.put("sessionId", sessionId.take(80))', protocol)
        server_paths = (
            "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
            "tv-google/app/src/main/java/com/pslink/tvgoogle/TvSubtitleServer.kt",
            "tv-fire/app/src/main/java/com/pslink/tvfire/TvSubtitleServer.kt",
            "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt",
        )
        for path in server_paths:
            with self.subTest(server=path):
                server = (ROOT / path).read_text(encoding="utf-8")
                self.assertRegex(
                    server,
                    r'\w+\??\.optInt\("v",\s*-1\)\s*(?:==|!=)\s*PsLinkProtocol.VERSION',
                )
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

    def test_pairing_servers_enforce_shared_attempt_limits(self):
        tv_servers = (
            "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
            "tv-google/app/src/main/java/com/pslink/tvgoogle/TvSubtitleServer.kt",
            "tv-fire/app/src/main/java/com/pslink/tvfire/TvSubtitleServer.kt",
        )
        for path in tv_servers:
            with self.subTest(server=path):
                server = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("private val pairingAttempts = PairingAttemptLimiter()", server)
                self.assertIn("pairingAttempts.isAllowed(pairingSource", server)
                self.assertIn("pairingAttempts.recordFailure(pairingSource", server)
                self.assertIn("pairingAttempts.recordSuccess(pairingSource)", server)

        phone_server = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("private val pairingAttempts = PairingAttemptLimiter()", phone_server)
        self.assertGreaterEqual(phone_server.count("pairingAttempts.isAllowed(pairingSource"), 3)
        self.assertIn("HttpStatusCode.TooManyRequests", phone_server)
        self.assertIn("pairingAttempts.recordFailure(pairingSource", phone_server)
        limiter = (
            ROOT / "core/shared/src/main/java/com/pslink/core/PairingAttemptLimiter.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("private val overflowBucketCount = minOf(32, maxTrackedSources)", limiter)
        self.assertIn("data class Overflow(val bucket: Int)", limiter)
        self.assertIn("if (key is SourceKey.Exact) failuresBySource.remove(key)", limiter)

    def test_mobile_media_config_carries_authenticated_session_id(self):
        client = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt"
        ).read_text(encoding="utf-8")
        self.assertRegex(
            client,
            r'\.put\("kind",\s*"media_config"\)\s*\.put\("sessionId",\s*sessionId\)',
        )

    def test_receivers_reject_stale_text_and_binary_frames(self):
        tv_servers = (
            "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
            "tv-google/app/src/main/java/com/pslink/tvgoogle/TvSubtitleServer.kt",
            "tv-fire/app/src/main/java/com/pslink/tvfire/TvSubtitleServer.kt",
        )
        for path in tv_servers:
            with self.subTest(server=path):
                server = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("activeSession.get() === sessionJob", server)
                self.assertIn('optString("sessionId", "") != authenticatedSessionId', server)
                self.assertIn('.put("sessionId", authenticatedSessionId)', server)
                self.assertIn('SessionIds.validate(json.optString("sessionId", "")) != activeSessionId.get()', server)
                self.assertIn('SessionIds.validate(auth.optString("sessionId", ""))', server)
                self.assertIn("originAllowlist.allows(call.request.headers[\"Origin\"])", server)

        client = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("rebindMediaConfig(mediaConfigJson, connectionSessionId)", client)
        self.assertIn('json.optString("sessionId", "") != connectionSessionId', client)
        self.assertIn("packet.copy(sessionId = sessionId)", client)

    def test_all_phone_receiver_routes_apply_origin_policy(self):
        phone = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt"
        ).read_text(encoding="utf-8")
        self.assertGreaterEqual(
            phone.count('originAllowlist.allows(call.request.headers["Origin"])'),
            5,
        )
        self.assertGreaterEqual(phone.count("pairingAttempts.isAllowed(pairingSource"), 3)
        self.assertIn(
            "private val sessions = ConcurrentHashMap<DefaultWebSocketServerSession, ActivePhoneSession>()",
            phone,
        )
        self.assertIn('SessionIds.validate(auth.optString("sessionId", ""))', phone)
        self.assertIn("sessionSlots.tryAcquire()", phone)
        self.assertIn("connectionSlots.tryAcquire()", phone)
        self.assertIn("connectionLeases.values.forEach { it.close() }", phone)
        self.assertIn("MAX_CONNECTIONS = 16", phone)
        self.assertNotIn("sessions.size >= MAX_SESSIONS", phone)
        self.assertIn('message.optString("sessionId", "") == authenticatedSessionId', phone)
        self.assertIn('.put("sessionId", authenticatedSessionId)', phone)
        self.assertIn("streamUrlMessage(sessionId)", phone)

    def test_hls_cors_and_receiver_transport_limits_are_explicit(self):
        phone = (
            ROOT / "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("HttpHeaders.AccessControlAllowOrigin", phone)
        self.assertIn("HttpHeaders.Vary", phone)
        self.assertIn("no-store, private", phone)
        self.assertIn("MAX_AUTH_FRAME_BYTES", phone)
        self.assertIn("MAX_CONTROL_FRAME_BYTES", phone)
        self.assertIn("MAX_HTTP_BODY_BYTES", phone)
        self.assertIn("MAX_SESSIONS", phone)
        self.assertIn("SESSION_IDLE_TIMEOUT_MS", phone)
        self.assertIn("call.receiveChannel()", phone)
        self.assertIn("BoundedByteAccumulator(limitBytes)", phone)
        self.assertIn("HttpStatusCode.PayloadTooLarge", phone)
        self.assertIn("CONTROL_FRAME_TOO_LARGE", phone)

        for path in (
            "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
            "tv-google/app/src/main/java/com/pslink/tvgoogle/TvSubtitleServer.kt",
            "tv-fire/app/src/main/java/com/pslink/tvfire/TvSubtitleServer.kt",
        ):
            with self.subTest(server=path):
                server = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("frame.data.size > PsLinkProtocol.MAX_CONTROL_FRAME_BYTES", server)
                self.assertIn("CONTROL_FRAME_TOO_LARGE", server)
                self.assertNotIn("frame.data.size > 64 * 1024) continue", server)
                self.assertIn("connectionSlots.tryAcquire()", server)
                self.assertIn("connectionLeases.values.forEach { it.close() }", server)

    def test_tv_media_queues_have_packet_and_byte_budgets(self):
        for path in (
            "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt",
            "tv-google/app/src/main/java/com/pslink/tvgoogle/TvMediaPlayer.kt",
            "tv-fire/app/src/main/java/com/pslink/tvfire/TvMediaPlayer.kt",
        ):
            with self.subTest(server=path):
                player = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("MAX_VIDEO_PACKET_BYTES", player)
                self.assertIn("MAX_AUDIO_PACKET_BYTES", player)
                self.assertIn("VIDEO_QUEUE_BYTE_BUDGET", player)
                self.assertIn("AUDIO_QUEUE_BYTE_BUDGET", player)
                self.assertIn("BoundedMediaQueue<VideoFrame>(", player)
                self.assertIn("runIfCurrent(queuedFrame)", player)
                self.assertNotIn("awaitingKeyFrame", player)

    def test_browser_receivers_bind_handshake_and_messages_to_each_connection(self):
        clients = (
            "receivers/web/pslink-client.js",
            "receivers/tizen/pslink-client.js",
            "receivers/webos/pslink-client.js",
            "receivers/xbox-edge/pslink-client.js",
        )
        for path in clients:
            with self.subTest(client=path):
                client = (ROOT / path).read_text(encoding="utf-8")
                self.assertIn("this.sessionId = createSessionId()", client)
                self.assertIn("sessionId: self.sessionId", client)
                self.assertIn('message.sessionId !== self.sessionId', client)
                self.assertIn("config.sessionId = this.sessionId", client)
                self.assertIn('message.kind === "heartbeat_ack"', client)


if __name__ == "__main__":
    unittest.main(verbosity=2)
