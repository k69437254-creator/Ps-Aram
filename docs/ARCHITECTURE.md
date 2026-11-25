# PS Link architecture notes

## Process boundary

- Mobile app owns the PlayStation session and all secrets stored by the upstream Chiaki-NG app.
- Chiaki-NG native code continues to own discovery, registration, session negotiation, packet handling and video presentation.
- Translation logic runs in the phone app process and does not proxy game video to a cloud service.
- TV app is a passive local-network subtitle renderer.

## Local receiver protocol v2

Endpoint: `ws://<tv-ip>:8765/subtitle`

First client message:

```json
{"v":2,"kind":"auth","code":"123456","client":"PS Link Mobile","sessionId":"<connection-id>"}
```

Server response after successful pairing:

```json
{"v":2,"kind":"ready"}
```

Subtitle and caption messages use protocol v2 JSON. The browser receivers send
`heartbeat` after readiness and expect `heartbeat_ack`; the server only accepts
these messages after a successful pairing handshake. The transport is local
network WebSocket (`ws://`) unless an external trusted TLS proxy is configured.

The server rejects invalid codes before accepting subtitle or media messages.
Authenticated sessions use a connection-specific `sessionId` on text control
messages, including captions, subtitles, and `media_config`. The six-digit
pairing code is generated with `SecureRandom` and persisted in TV preferences.
Pairing endpoints limit failed attempts to five per remote source per rolling
60 seconds. The code is still sent over cleartext WebSocket; throttling does not
provide transport encryption or strong device identity. Restrict this prototype
to a trusted local network and never expose port 8765 directly to the Internet.

Browser receivers hosted over HTTPS need a trusted WSS/HTTPS reverse proxy.
That protects the browser-to-proxy hop only; the proxy-to-phone hop is still
cleartext unless separately secured. The Origin allowlist is enforced but is
empty by default, so browser Origins are rejected until configured. Android
builds accept a comma-separated exact allowlist through
`-Ppslink.allowedOrigins=https://receiver.example`; unlisted browser Origins
are rejected. Origin-less native clients remain supported and still rely on
the pairing code. Android Mobile can connect to an explicit
`wss://host/subtitle` endpoint using the system trust store; it does not bypass
certificate verification. A reverse proxy must also provide trusted HTTPS for
HLS playlist and segment requests. Do not treat the optional proxy as
end-to-end encryption if its upstream hop remains cleartext.

## Media paths and audio limits

The Android TV binary WebSocket path is a separate Chiaki bridge: the native
decoder callback emits H.264/H.265 access units and decoded PCM to the phone
client. It is not the phone's MediaProjection HLS path, and the current CI
failure means this native bridge has not been compiled or validated at runtime
for this commit.

The HLS path captures the Android display and optionally uses Android 10+
`AudioPlaybackCaptureConfiguration` for `USAGE_MEDIA`/`USAGE_GAME`. Capture
depends on runtime permission and the source app's capture policy; it is not
proof that Chiaki/PS5 game audio is present. The FFmpeg AAC fixture validates
MPEG-TS/AAC packaging only, not real game-audio capture or A/V synchronization.

## Live OCR and translation

The phone samples the Chiaki `SurfaceView` periodically with Android `PixelCopy`;
it does not intercept every native decoder frame. Latin, Japanese, Chinese and
Korean text uses ML Kit OCR and on-device translation to Arabic. Arabic source
text uses Tesseract with the bundled
`mobile/src/main/assets/tessdata/ara.traineddata` model and is displayed without
translation. Sampling can miss short dialogue, and OCR accuracy, thermal
behavior and long-session impact still require device testing.
