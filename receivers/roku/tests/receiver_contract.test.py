#!/usr/bin/env python3
from pathlib import Path

root = Path(__file__).resolve().parents[3]
pair = (root / "receivers/roku/source/components/PairingTask.brs").read_text()
poll = (root / "receivers/roku/source/components/SubtitlePollTask.brs").read_text()
scene = (root / "receivers/roku/source/MainScene.brs").read_text()

# WSS pairing and trusted Roku CA bundle.
assert 'secure = Left(LCase(host), 6) = "wss://"' in pair
assert 'wsUrl = "wss://" + authority + "/subtitle"' in pair
assert 'ws.SetCertificatesFile("common:/certs/ca-bundle.crt")' in pair
assert 'ws.SetUrl(wsUrl)' in pair
assert 'wsUrl = "ws://" + host + ":8765/subtitle"' in pair
assert 'ws.SetUrl("ws://" + host + ":8765/subtitle")' not in pair
assert 'ws.Open(10000)' in pair and 'ws.Send(authMsg)' in pair

# HTTPS subtitle polling, certificate setup, and legacy HTTP mode.
assert 'requestUrl = "https://" + authority + "/receiver/roku/subtitle"' in poll
assert 'transfer.SetCertificatesFile("common:/certs/ca-bundle.crt")' in poll
assert 'requestUrl = "http://" + host + ":8765/receiver/roku/subtitle"' in poll
assert 'transfer.SetUrl(requestUrl)' in poll
assert 'secureProxyAuthority(host)' in poll
assert "latestSequence = parsed.latestSequence" in poll
assert "oldestSequence = parsed.oldestSequence" in poll
assert "latestSequence < cursor" in poll
assert "cursor = oldestSequence - 1" in poll
assert "cursor = latestSequence" in poll

# HLS authority rewriting must preserve only a validated /hls/.../index.m3u8 path.
assert 'rewriteSecureHlsUrl(m.task.url, m.top.phoneHost)' in scene
assert 'content.HttpCertificatesFile = "common:/certs/ca-bundle.crt"' in scene
assert 'return "https://" + authority + path' in scene
assert 'Left(LCase(path), 5) <> "/hls/"' in scene
assert 'Right(LCase(path), 10) <> "index.m3u8"' in scene
assert 'Instr(1, path, "?") > 0 or Instr(1, path, "#") > 0' in scene
assert 'content.streamFormat = "hls"' in scene
assert 'm.subtitleTask.control = "RUN"' in scene
assert 'if parsed.clear = true' in scene

# All proxy config parsers reject path/query/fragment/user-info.
for source in (pair, poll, scene):
    assert 'Instr(1, authority, "?") > 0' in source
    assert 'Instr(1, authority, "@") > 0' in source

print("PASS: Roku secure-proxy and legacy-LAN source-contract checks.")
