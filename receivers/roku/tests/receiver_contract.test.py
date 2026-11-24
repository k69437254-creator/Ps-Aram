#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[3]
pair = (root / "receivers/roku/source/components/PairingTask.brs").read_text()
scene = (root / "receivers/roku/source/MainScene.brs").read_text()

assert "isSixDigitCode(code)" in pair
assert "isValidStreamUrl(url)" in pair
assert 'CreateObject("roWebSocket")' in pair
assert 'ws.SetUrl("ws://" + host + ":8765/subtitle")' in pair
assert "ws.Open(10000)" in pair
assert "ws.Send(authMsg)" in pair
assert 'type(msg) = "roWebSocketEvent"' in pair
assert "msg.GetType()" in pair
assert "msg.GetInfo()" in pair
assert "info.Text" in pair
assert "ws.connect(" not in pair
assert "ws.sendText(" not in pair
assert "getConnectionState()" not in pair
assert 'Right(lowerPath, 5) <> ".m3u8"' in pair
assert 'Right(lowerPath, 4) <> ".m3u"' in pair
main_scene = scene
assert 'Right(lowerPath, 5) <> ".m3u8"' in main_scene
assert 'Right(lowerPath, 4) <> ".m3u"' in main_scene

assert "isSixDigitCode(code)" in scene
assert "isValidStreamUrl(url)" in scene
assert "content.streamFormat = \"hls\"" in scene
assert 'm.subtitleTask.control = "RUN"' in scene

print("PASS: Roku pairing and HLS URL contract checks.")
