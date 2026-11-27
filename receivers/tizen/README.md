# PS Link for Samsung Tizen TV

## Implemented receiver features

- PS Link v2 WebSocket pairing with a six-digit pairing code.
- Shared PS Link transport client.
- Arabic subtitle and caption text rendering.
- Samsung AVPlay adapter for HLS stream URLs received from the paired phone.
- Playback controls and playback-state feedback.
- Handling for WebSocket disconnection, AVPlay errors, buffering and stream completion.
- Samsung TV remote navigation using directional keys and Enter.
- Samsung Back key handling in the application.
- Spatial caption positioning when incoming caption lines provide normalized
  coordinates.

## Video and caption behavior

The receiver accepts a protocol v2 `stream_url` message and passes its URL
to Samsung AVPlay. Raw media packets are not decoded by this web receiver.

Spatial captions are mapped onto the available video content rectangle using
normalized line coordinates. If spatial rendering is unavailable or invalid,
caption text is shown in the regular subtitle panel instead.

The receiver does not currently establish calibrated game-specific geometry
or guarantee frame-accurate caption synchronization with the video.

## Media limitations

The current phone HLS stream provides H.264 video only. Complete captured
game audio and verified audio/video synchronization are not established.

## Remaining integration and device validation

The phone-side HLS publisher remains a separate integration requirement.
Automatic device discovery is not implemented.

WGT packaging, signing, installation and physical Samsung TV testing remain
separate validation tasks. Source-level checks and simulated tests do not
prove that a signed application works on physical television hardware.

Validate pairing, reconnection, subtitle delivery, spatial caption placement,
HLS playback, remote navigation and long-session stability on supported
Samsung Tizen devices.
