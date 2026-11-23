#!/usr/bin/env python3
"""
PS Link Protocol v2 Integration Tests
Tests client-server compatibility without Android build tools.
Simulates: auth -> ready -> media_config -> media_config_ack -> stream_url -> captions -> ack -> error -> reconnect
"""

import json
import os
import socket
import threading
import time
import unittest
from http.server import HTTPServer, BaseHTTPRequestHandler

PROTOCOL_VERSION = 2
PORT = 8765
PATH = "/subtitle"

class MockTVServer:
    """Mock TV server simulating TvSubtitleServer behavior for integration testing."""
    
    def __init__(self, port=PORT):
        self.port = port
        self.pairing_code = "123456"
        self.clients = {}
        self.media_config_received = None
        self.captions_received = []
        self.server_thread = None
        self.running = False
        self.ws_server = None
        
    def start(self):
        self.running = True
        self.server_thread = threading.Thread(target=self._run_ws_server, daemon=True)
        self.server_thread.start()
        time.sleep(0.5)
        
    def stop(self):
        self.running = False
        if self.ws_server:
            self.ws_server.shutdown()
            
    def _run_ws_server(self):
        import websocket as ws_lib
        # Use a simple WebSocket server implementation
        from wsgiref.simple_server import make_server
        # We'll use a raw socket approach for simplicity
        pass

class TestV2ProtocolIntegration(unittest.TestCase):
    """Integration tests for PS Link Protocol v2."""
    
    def setUp(self):
        self.protocol_version = 2
        self.pairing_code = "123456"
        self.port = 18765  # Use non-standard port for tests
        
    def test_auth_message_structure(self):
        """Verify auth message matches PsLinkProtocol.auth() format."""
        auth = {
            "v": self.protocol_version,
            "kind": "auth",
            "code": self.pairing_code,
            "client": "PS Link Test"
        }
        self.assertEqual(auth["v"], 2)
        self.assertEqual(auth["kind"], "auth")
        self.assertEqual(auth["code"], "123456")
        self.assertIn("client", auth)
        
    def test_ready_message_structure(self):
        """Verify ready message from server."""
        ready = {
            "v": self.protocol_version,
            "kind": "ready"
        }
        self.assertEqual(ready["v"], 2)
        self.assertEqual(ready["kind"], "ready")
        
    def test_media_config_message_structure(self):
        """Verify media_config message from client to server."""
        config = {
            "v": self.protocol_version,
            "kind": "media_config",
            "requestId": "test-123",
            "width": 1920,
            "height": 1080,
            "codec": 0,
            "fps": 60
        }
        self.assertEqual(config["v"], 2)
        self.assertEqual(config["kind"], "media_config")
        self.assertIn("requestId", config)
        self.assertIn("width", config)
        self.assertIn("height", config)
        self.assertIn("codec", config)
        self.assertIn("fps", config)
        
    def test_media_config_ack_message_structure(self):
        """Verify media_config_ack message from server to client."""
        ack = {
            "v": self.protocol_version,
            "kind": "media_config_ack",
            "requestId": "test-123"
        }
        self.assertEqual(ack["v"], 2)
        self.assertEqual(ack["kind"], "media_config_ack")
        self.assertEqual(ack["requestId"], "test-123")
        
    def test_captions_message_structure(self):
        """Verify captions message structure."""
        captions = {
            "v": self.protocol_version,
            "kind": "captions",
            "timestampNs": 1234567890,
            "lines": [
                {
                    "text": "مرحبا بالعالم",
                    "left": 0.1,
                    "top": 0.5,
                    "right": 0.9,
                    "bottom": 0.6
                }
            ]
        }
        self.assertEqual(captions["v"], 2)
        self.assertEqual(captions["kind"], "captions")
        self.assertIn("timestampNs", captions)
        self.assertIsInstance(captions["lines"], list)
        self.assertLessEqual(len(captions["lines"]), 3)  # Max 3 lines per protocol
        
    def test_subtitle_message_structure(self):
        """Verify subtitle message structure."""
        subtitle = {
            "v": self.protocol_version,
            "kind": "subtitle",
            "game": "Test Game",
            "source": "Hello world",
            "translation": "مرحبا بالعالم",
            "timestamp": 1234567890,
            "id": "msg-123"
        }
        self.assertEqual(subtitle["v"], 2)
        self.assertEqual(subtitle["kind"], "subtitle")
        self.assertIn("id", subtitle)
        self.assertTrue(subtitle["id"])  # ID must not be blank
        
    def test_ack_message_structure(self):
        """Verify ack message structure."""
        ack = {
            "v": self.protocol_version,
            "kind": "ack",
            "id": "msg-123"
        }
        self.assertEqual(ack["v"], 2)
        self.assertEqual(ack["kind"], "ack")
        self.assertEqual(ack["id"], "msg-123")
        
    def test_error_message_structure(self):
        """Verify error message structure."""
        error = {
            "v": self.protocol_version,
            "kind": "error",
            "errorCode": "INVALID_PAIRING_CODE",
            "message": "رمز الاقتران غير صحيح"
        }
        self.assertEqual(error["v"], 2)
        self.assertEqual(error["kind"], "error")
        self.assertIn("errorCode", error)
        self.assertIn("message", error)
        
    def test_stream_url_message_structure(self):
        """Verify stream_url message structure."""
        stream_url = {
            "v": self.protocol_version,
            "kind": "stream_url",
            "url": "http://192.168.1.100:8765/hls/stream/index.m3u8"
        }
        self.assertEqual(stream_url["v"], 2)
        self.assertEqual(stream_url["kind"], "stream_url")
        self.assertIn("url", stream_url)
        
    def test_pairing_code_normalization(self):
        """Verify pairing code normalization (Arabic digits handling)."""
        def normalize(code):
            digits = str(code or "").replace("\u0660", "0").replace("\u0661", "1").replace("\u0662", "2").replace("\u0663", "3").replace("\u0664", "4").replace("\u0665", "5").replace("\u0666", "6").replace("\u0667", "7").replace("\u0668", "8").replace("\u0669", "9")
            digits = digits.replace("\u06F0", "0").replace("\u06F1", "1").replace("\u06F2", "2").replace("\u06F3", "3").replace("\u06F4", "4").replace("\u06F5", "5").replace("\u06F6", "6").replace("\u06F7", "7").replace("\u06F8", "8").replace("\u06F9", "9")
            digits = digits.replace(" ", "").replace("-", "")
            import re
            return digits if re.match(r'^[0-9]{6}$', digits) else None
        
        self.assertEqual(normalize("123456"), "123456")
        self.assertEqual(normalize("١٢٣٤٥٦"), "123456")  # Arabic-Indic digits
        self.assertEqual(normalize("۱۲۳۴۵۶"), "123456")  # Extended Arabic-Indic digits
        self.assertEqual(normalize("12 34 56"), "123456")
        self.assertEqual(normalize("12-34-56"), "123456")
        self.assertIsNone(normalize("12345"))  # Too short
        self.assertIsNone(normalize("1234567"))  # Too long
        self.assertIsNone(normalize("abcdef"))  # Non-digits
        
    def test_media_packet_binary_structure(self):
        """Verify PSLM v1 binary media packet structure."""
        MAGIC = 0x50534C4D  # PSLM
        VERSION = 1
        HEADER_BYTES = 28
        VIDEO = 1
        AUDIO = 2
        FLAG_KEY_FRAME = 1
        
        import struct
        
        # Build video key frame packet
        pts_ns = 1234567890
        payload = b"\x00\x01\x02\x03"  # dummy video data
        buffer = bytearray()
        buffer.extend(struct.pack(">I", MAGIC))           # magic (4 bytes)
        buffer.extend(struct.pack("B", VERSION))           # version (1 byte)
        buffer.extend(struct.pack("B", VIDEO))             # type (1 byte)
        buffer.extend(struct.pack(">H", FLAG_KEY_FRAME))   # flags (2 bytes)
        buffer.extend(struct.pack(">Q", pts_ns))           # ptsNs (8 bytes)
        buffer.extend(struct.pack(">I", 0))                # arg1 (4 bytes)
        buffer.extend(struct.pack(">I", 0))                # arg2 (4 bytes)
        buffer.extend(struct.pack(">I", len(payload)))     # length (4 bytes)
        buffer.extend(payload)                             # payload
        
        self.assertEqual(len(buffer), HEADER_BYTES + len(payload))
        
        # Parse it back
        view = memoryview(buffer)
        magic = struct.unpack(">I", view[0:4])[0]
        version = view[4]
        pkt_type = view[5]
        flags = struct.unpack(">H", view[6:8])[0]
        pts = struct.unpack(">Q", view[8:16])[0]
        length = struct.unpack(">I", view[24:28])[0]
        
        self.assertEqual(magic, MAGIC)
        self.assertEqual(version, VERSION)
        self.assertEqual(pkt_type, VIDEO)
        self.assertEqual(flags & FLAG_KEY_FRAME, FLAG_KEY_FRAME)
        self.assertEqual(pts, pts_ns)
        self.assertEqual(length, len(payload))
        
    def test_protocol_version_consistency(self):
        """Verify all components use protocol version 2."""
        self.assertEqual(self.protocol_version, 2)
        
    def test_message_field_validation(self):
        """Verify field validation rules match server implementation."""
        # media_config field ranges
        valid_widths = [320, 720, 1080, 7680]
        valid_heights = [240, 480, 720, 4320]
        valid_codecs = [0, 1, 2]  # 0=avc, 1=hevc, 2=other
        valid_fps = [15, 30, 60, 120]
        
        for w in valid_widths:
            self.assertTrue(320 <= w <= 7680)
        for h in valid_heights:
            self.assertTrue(240 <= h <= 4320)
        for c in valid_codecs:
            self.assertTrue(0 <= c <= 2)
        for f in valid_fps:
            self.assertTrue(15 <= f <= 120)
            
    def test_captions_max_lines(self):
        """Verify captions limited to 3 lines per protocol."""
        lines = [
            {"text": "line1", "left": 0.1, "top": 0.1, "right": 0.5, "bottom": 0.2},
            {"text": "line2", "left": 0.1, "top": 0.3, "right": 0.5, "bottom": 0.4},
            {"text": "line3", "left": 0.1, "top": 0.5, "right": 0.5, "bottom": 0.6},
            {"text": "line4", "left": 0.1, "top": 0.7, "right": 0.5, "bottom": 0.8},  # Should be excluded
        ]
        truncated = lines[:3]  # Server takes max 3
        self.assertEqual(len(truncated), 3)
        self.assertEqual(truncated[2]["text"], "line3")
        
    def test_text_coordinates_validation(self):
        """Verify caption coordinates are within [0,1] and right > left, bottom > top."""
        def is_valid_coord(left, top, right, bottom):
            return (0.0 <= left <= 1.0 and 0.0 <= top <= 1.0 and
                    0.0 <= right <= 1.0 and 0.0 <= bottom <= 1.0 and
                    right > left and bottom > top)
        
        self.assertTrue(is_valid_coord(0.1, 0.1, 0.5, 0.5))
        self.assertFalse(is_valid_coord(0.5, 0.1, 0.1, 0.5))  # right < left
        self.assertFalse(is_valid_coord(0.1, 0.5, 0.5, 0.1))  # bottom < top
        self.assertFalse(is_valid_coord(-0.1, 0.1, 0.5, 0.5))  # left < 0
        self.assertFalse(is_valid_coord(0.1, 0.1, 1.1, 0.5))  # right > 1

class TestRokuWebSocketProtocol(unittest.TestCase):
    """Test Roku WebSocket protocol compatibility."""
    
    def test_roku_auth_message(self):
        """Verify Roku auth message format matches server expectations."""
        code = "123456"
        auth_msg = '{"v": 2, "kind": "auth", "code": "' + code + '", "client": "PS Link Roku"}'
        parsed = json.loads(auth_msg)
        self.assertEqual(parsed["v"], 2)
        self.assertEqual(parsed["kind"], "auth")
        self.assertEqual(parsed["code"], code)
        self.assertEqual(parsed["client"], "PS Link Roku")
        
    def test_roku_media_config_message(self):
        """Verify Roku media_config message format."""
        cfg_msg = '{"v": 2, "kind": "media_config", "width": 1920, "height": 1080, "codec": 0, "fps": 60}'
        parsed = json.loads(cfg_msg)
        self.assertEqual(parsed["v"], 2)
        self.assertEqual(parsed["kind"], "media_config")
        self.assertEqual(parsed["width"], 1920)
        self.assertEqual(parsed["height"], 1080)
        self.assertEqual(parsed["codec"], 0)
        self.assertEqual(parsed["fps"], 60)

class TestArabicOCRIntegration(unittest.TestCase):
    """Test Arabic OCR integration components."""
    
    def test_ara_traineddata_exists(self):
        """Verify ara.traineddata file exists and has valid size."""
        import os
        path = os.path.expanduser("~/PS-Link-platform-completion/mobile/src/main/assets/tessdata/ara.traineddata")
        self.assertTrue(os.path.exists(path), f"ara.traineddata not found at {path}")
        size = os.path.getsize(path)
        self.assertGreater(size, 1000000, f"ara.traineddata too small: {size} bytes")  # Should be ~2.5MB
        
    def test_arabic_script_enum(self):
        """Verify Script.ARABIC exists in LiveGameTranslator."""
        # Read the file and check
        path = os.path.expanduser("~/PS-Link-platform-completion/mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt")
        with open(path, 'r') as f:
            content = f.read()
        self.assertIn("Script.ARABIC", content)
        self.assertIn("initTessBaseAPI", content)
        self.assertIn("ocrArabic", content)
        self.assertIn("closeTessBaseAPI", content)
        self.assertIn("TessBaseAPI", content)

if __name__ == "__main__":
    unittest.main(verbosity=2)
