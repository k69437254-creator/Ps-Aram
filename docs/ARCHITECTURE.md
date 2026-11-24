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
{"v":2,"kind":"auth","code":"123456","client":"PS Link Mobile"}
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
The six-digit pairing code is short-lived and is not a substitute for transport
encryption or strong device identity.

## Live OCR and translation

The phone samples the Chiaki `SurfaceView` periodically with Android `PixelCopy`;
it does not intercept every native decoder frame. Latin, Japanese, Chinese and
Korean text uses ML Kit OCR and on-device translation to Arabic. Arabic source
text uses Tesseract with the bundled
`mobile/src/main/assets/tessdata/ara.traineddata` model and is displayed without
translation. Sampling can miss short dialogue, and OCR accuracy, thermal
behavior and long-session impact still require device testing.
