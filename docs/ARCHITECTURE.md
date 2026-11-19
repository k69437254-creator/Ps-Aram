# PS Link architecture notes

## Process boundary

- Mobile app owns the PlayStation session and all secrets stored by the upstream Chiaki-NG app.
- Chiaki-NG native code continues to own discovery, registration, session negotiation, packet handling and video presentation.
- Translation logic runs in the phone app process and does not proxy game video to a cloud service.
- TV app is a passive local-network subtitle renderer.

## Local subtitle protocol v1

Endpoint: `ws://<tv-ip>:8765/subtitle`

First client message:

```json
{"v":1,"kind":"auth","code":"123456","client":"PS Link Mobile"}
```

Server response after successful pairing:

```json
{"v":1,"kind":"ready"}
```

Subtitle packet:

```json
{"v":1,"kind":"subtitle","game":"Game title","source":"Source line","translation":"Arabic line","timestamp":1730000000000}
```

The server rejects invalid codes before accepting subtitle messages. The code is ephemeral and rotates on TV app restart. This handshake is intended to avoid accidental LAN clients, not to provide transport encryption or strong device identity.

## Why live OCR is not wired yet

The current upstream Android renderer passes a Surface into the native session through JNI. Pulling each frame back into Kotlin would add copies and could increase memory bandwidth and latency. A production implementation should add an opt-in bounded frame sink in the native video decoder path, reuse buffers, apply sampling and backpressure, and verify that the video presentation thread is never blocked by OCR. That native modification has not been done in this source package.
