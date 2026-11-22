
PS Link TV receiver expansion

This directory defines the platform boundary for non-Android TV receivers.
A platform is not considered supported merely because a project folder exists.

Target matrix

Platform| Native application technology| State
Android TV / Google TV| Android APK / MediaCodec| Existing Android receiver; Google TV device compatibility still requires validation
Amazon Fire TV| Fire OS / Android APK| Not validated; compatibility work required
Samsung Smart TV| Tizen Web application| Not implemented
LG Smart TV| webOS TV application / IPK| Not implemented
Roku TV / Roku player| SceneGraph / BrightScript| Not implemented
Xbox| UWP / Windows application| Not implemented

Existing transport contract

The Android receiver currently uses:

- WebSocket: "ws://<tv-ip>:8765/subtitle"
- Protocol version: "2"
- Authentication: "{v:2, kind:"auth", code:"123456", client:"..."}"
- Success response: "{v:2, kind:"ready"}"
- Subtitle messages: "kind:"subtitle"" with "game", "source",
  "translation", "timestamp", and "id"
- Caption overlay messages: "kind:"captions"" with "timestampNs" and
  normalized "lines"
- Media configuration: "kind:"media_config"" with "width", "height",
  "codec", and "fps"
- Binary media: custom "PSLM" v1 packets with a 28-byte big-endian header,
  followed by a compressed video access unit or PCM16 audio payload.

Important media limitation

The binary media transport is a low-level custom elementary-stream format.
Tizen AVPlay, webOS media APIs, Roku Video nodes, and Xbox Media Foundation
do not expose one identical push-decoder interface.

A complete receiver therefore needs a tested native media path on each OS,
or a future transport redesign around a portable delivery format. A successful
WebSocket handshake alone does not mean game video and audio are supported.

Implementation sequence

1. Preserve the existing Android receiver and wire protocol.
2. Complete Tizen and webOS apps using the shared browser transport and
   separate native media adapters.
3. Implement Roku using SceneGraph/BrightScript and its native media pipeline.
4. Implement Xbox using its Windows networking and Media Foundation pipeline.
5. Validate codecs, frame pacing, audio, captions, pairing, reconnect, and
   long-running sessions on physical devices for each supported OS generation.

Do not advertise a platform as fully supported until its application builds
and its complete media path passes device testing.
## Phone HLS receiver (Tizen / webOS)

The phone-side receiver endpoint uses the PS Link v2 WebSocket pairing route on port `8765`. After the first complete H.264 IDR-aligned MPEG-TS segment is published, it sends an authenticated `stream_url` message pointing to `/hls/index.m3u8`. TV receiver clients retry temporary network failures with capped exponential backoff and stop retrying after an authentication rejection or a manual disconnect. This path is separate from the existing Android TV binary-media receiver.
