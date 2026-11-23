
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

## Platform completion pack

The `platforms/targets.json` file records the current implementation state
and validation requirements for each target.

Generated targets:

- `receivers/roku`: BrightScript / SceneGraph receiver prototype.
  It requests an authenticated HLS URL from the phone.
- `receivers/xbox-edge`: browser receiver prototype for Edge on Xbox.
  This is not a native Xbox application.

The phone exposes `POST /receiver/roku/stream-url` on the existing PS Link
port. The endpoint checks the six-digit pairing code and returns the HLS URL
only after the first playlist is ready.

Media limitation: the current HLS publisher carries H.264 video. It does
not establish complete audio support. Do not advertise full audio/video
support for Roku, Tizen, webOS or Xbox until the audio path is implemented
and tested on physical devices.

Xbox Edge production hosting requires secure WebSocket/HTTP delivery through
a trusted reverse proxy if the receiver page is served over HTTPS.

<!-- PSLINK_PLATFORM_COMPLETION_DOCS_V1 -->
## Platform status and receiver limitations

The receiver implementations have different levels of completion.
A source implementation or successful pairing handshake does not prove
complete platform compatibility or physical-device validation.

| Platform | Current implementation | Remaining validation |
|---|---|---|
| Android TV | Native Android receiver | Build and physical-device media tests |
| Google TV | Android TV compatibility target | Physical-device compatibility |
| Amazon Fire TV | Android APK compatibility target | Fire OS device validation |
| Samsung Tizen | Web app with AVPlay HLS adapter | Packaging, signing and device tests |
| LG webOS | Web app with HTML5 HLS adapter | Packaging, signing and device tests |
| Roku | SceneGraph/BrightScript receiver | Packaging, installation and device tests |
| Xbox Edge | Browser-based receiver | Trusted HTTPS/WSS hosting and Xbox Edge tests |

### Phone transport

The phone receiver exposes protocol v2 WebSocket at `/subtitle` on port
`8765`. The current Roku implementation uses its HTTP polling path for
subtitles rather than the browser WebSocket client.

### Media limitations

The current phone HLS publisher carries H.264 video. It does not establish
complete captured game audio or verified audio/video synchronization.

Tizen and webOS caption panels display text; they are not calibrated
geometric overlays on the video. Roku's current HLS path is video-only.
Xbox Edge is a browser receiver, not a native Xbox application.

Physical-device validation must be recorded separately from automated
source tests.
<!-- /PSLINK_PLATFORM_COMPLETION_DOCS_V1 -->
