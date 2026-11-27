# PS Link for LG webOS TV

## Implemented receiver source

- PS Link v2 WebSocket pairing and six-digit pairing UI.
- Shared PS Link transport client.
- Arabic subtitle and caption rendering.
- HTML5 video HLS playback adapter.
- Playback mode styling and disconnect control.
- Media buffering, playback completion and error events.
- Cleanup of playback and captions after disconnect or connection loss.
- Spatial caption rendering when a valid stream is active, with text fallback.

## Media limitations

The receiver uses the phone-provided HLS stream. The current phone HLS
pipeline provides H.264 video; complete captured game audio and verified
audio/video synchronization are not established by these source tests.

Raw transport media packets are not decoded directly by the webOS HTML
video element.

## Validation limits

Source-level tests do not prove playback on a physical LG television.
IPK packaging, signing, installation, supported webOS-version compatibility,
and real-device playback validation remain separate tasks.

Validate pairing, reconnection, HLS playback, captions, buffering,
completion, and long-session stability on supported webOS hardware.

<!-- PSLINK_WEBOS_IMPLEMENTATION_V3 -->
