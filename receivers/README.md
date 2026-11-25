
PS Link TV receiver expansion

This directory defines the platform boundary for non-Android TV receivers.
A platform is not considered supported merely because a project folder exists.

Target matrix

| Platform | Application technology | Current implementation |
|---|---|---|
| Android TV | Native Android APK / MediaCodec | Native binary-media and subtitle receiver |
| Google TV | Separate Android APK module | Native receiver module; device validation pending |
| Fire TV | Separate Android APK module | Native receiver module; Fire OS validation pending |
| Samsung Tizen | Tizen Web application / AVPlay | HLS video playback adapter; vendor package/device validation pending |
| LG webOS | webOS application / HTML media | HLS playback adapter; vendor package/device validation pending |
| Roku | SceneGraph / BrightScript | HLS video and subtitle prototype; audio/device validation pending |
| Web browser | Browser application | WebSocket, HLS and binary media client |
| Xbox Edge | Browser receiver | Edge-compatible web receiver; not a native Xbox application |

Existing transport contract

The Android receiver currently uses:

- WebSocket: `ws://<tv-ip>:8765/subtitle` on trusted LAN; clients also accept
  explicit `wss://<trusted-host>/subtitle` and validate TLS using the platform
  trust store when connecting through a trusted TLS endpoint.
- Protocol version: "2"
- Authentication: `{"v":2,"kind":"auth","code":"123456","client":"...",` plus
  a connection-specific `sessionId` for Android TV/Google TV/Fire TV.
- Success response: "{v:2, kind:"ready"}"
- Subtitle messages: "kind:"subtitle"" with "game", "source",
  "translation", "timestamp", and "id"
- Caption overlay messages: "kind:"captions"" with "timestampNs" and
  normalized "lines" and the authenticated `sessionId`
- Media configuration: "kind:"media_config"" with "width", "height",
  "codec", "fps", and the authenticated `sessionId`
- Binary media: custom "PSLM" v1 packets with a 28-byte big-endian header,
  followed by a compressed video access unit or PCM16 audio payload.

Important media limitation

The binary media transport is a low-level custom elementary-stream format.
Tizen AVPlay, webOS media APIs, Roku Video nodes, and Xbox Media Foundation
do not expose one identical push-decoder interface.

A complete receiver therefore needs a tested native media path on each OS,
or a future transport redesign around a portable delivery format. A successful
WebSocket handshake alone does not mean game video and audio are supported.

The targets listed above have source implementations at different maturity
levels. Keep their media adapters platform-specific and validate codec support,
pairing, reconnect, captions, audio, frame pacing, and long-running sessions on
physical devices before declaring any target fully supported.

Do not advertise a platform as fully supported until its application builds
and its complete media path passes device testing.

For browser deployments, build Android apps with
`-Ppslink.allowedOrigins=https://receiver.example`; the exact Origin allowlist
is empty by default and rejects requests carrying an unconfigured Origin.
Native clients that omit Origin remain accepted. Local `ws://` and `http://`
paths remain unencrypted and must not be exposed outside a trusted LAN.

A TLS reverse proxy must forward `/subtitle` and `/hls/*` to the phone server
without dropping WebSocket upgrade headers. Point web clients to
`wss://receiver.example/subtitle`; the secure-page HLS adapter maps the phone's
`/hls/*` URL onto the same TLS origin. The proxy certificate must be trusted by
each receiver platform; do not disable certificate validation.

Example Caddy site (replace the hostname and phone address):

```caddyfile
receiver.example {
    @websocket path /subtitle
    reverse_proxy @websocket 192.168.1.20:8765

    @hls path /hls/*
    reverse_proxy @hls 192.168.1.20:8765
}
```

Caddy provisions HTTPS only when the hostname is publicly resolvable and its
certificate challenge can complete. Build the Android apps with that page
Origin explicitly allowed, for example
`-Ppslink.allowedOrigins=https://receiver.example`. This secures browser-to-
proxy traffic; Caddy-to-phone traffic is still cleartext HTTP/WS on the LAN.
Keep that segment trusted and do not expose port 8765 publicly.
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

Media limitation: the phone HLS path prioritizes decoded Chiaki PCM, encodes AAC-LC,
and uses Android Playback Capture only as a mutually exclusive fallback when
Chiaki PCM is absent or its encoder fails. H.264 and AAC access units pass through
a bounded shared-PTS reorder window before muxing. This source-level integration
does not prove PS5 audio capture, long-session A/V synchronization, or playback
on receiver devices; validate real media and hardware before advertising full support.

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

The phone HLS implementation now routes Chiaki decoded PCM through AAC-LC encoding
first and starts Playback Capture only as an exclusive fallback when Chiaki audio
is unavailable. Its bounded PTS scheduler improves callback ordering, but
synthetic tests do not prove real PS5 audio capture or hardware-clock drift correction.

Tizen and webOS caption panels display text; they are not calibrated
geometric overlays on the video. Roku's HLS audio handling is not validated.
Xbox Edge is a browser receiver, not a native Xbox application.

Physical-device validation must be recorded separately from automated
source tests.
<!-- /PSLINK_PLATFORM_COMPLETION_DOCS_V1 -->

<!-- PSLINK_REMAINING_REPAIRS_V3_HLS_AUDIO_PATH -->
