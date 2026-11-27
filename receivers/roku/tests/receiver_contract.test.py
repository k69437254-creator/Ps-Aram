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


# Scene playback lifecycle and fresh pairing-session cleanup.
assert 'm.player.observeField("state", "onPlayerState")' in scene
assert 'sub onPlayerState()' in scene
assert 'state = "error"' in scene
assert 'state = "finished"' in scene
assert 'state = "playing"' in scene
assert 'state = "buffering"' in scene
assert 'm.subtitleTask.control = "STOP"' in scene
assert 'm.task.url = ""' in scene
assert 'm.task.error = ""' in scene


# Task.control is write-only; task execution must be inspected through state.
assert 'if m.task.state = "run" then return' in scene
assert 'if m.task.control = "RUN" then return' not in scene

assert pair.count('m.top.state = "run"') == 4
assert 'm.top.control = "RUN" and' not in pair
assert 'm.top.control = "RUN"' not in pair

assert 'while m.top.state = "run"' in poll
assert 'while m.top.control = "RUN"' not in poll

# Keep control writes as the actual start/stop commands.
assert 'm.task.control = "RUN"' in scene
assert 'm.task.control = "STOP"' in scene
assert 'm.subtitleTask.control = "RUN"' in scene
assert 'm.subtitleTask.control = "STOP"' in scene


# Back navigation must stop the subtitle timer as well as polling and playback.
back_start = scene.index("function onKeyEvent")
back_end = scene.index("end function", back_start)
back_handler = scene[back_start:back_end]
assert 'm.subtitleTask.control = "STOP"' in back_handler
assert 'm.subtitleTimer.control = "stop"' in back_handler
assert 'm.player.control = "stop"' in back_handler

print("PASS: Roku secure-proxy and legacy-LAN source-contract checks.")
