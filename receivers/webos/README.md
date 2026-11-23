# PS Link for LG webOS TV

webOS TV web application package source. Import this directory into the
LG webOS TV SDK/CLI as an application, then package and install it using
the webOS TV developer tools.

Implemented:
- PS Link v2 WebSocket pairing.
- Six-digit pairing UI.
- Arabic subtitle and caption rendering.
- Disconnect handling and remote Escape-key disconnect.
- Shared PS Link transport client.

Not implemented:
- Compressed video decoding or audio playback.
- Native webOS media adapter.
- Automatic device discovery.
- Physical-device validation.

Media packets are detected but not rendered. This is subtitle receiver
support only, not complete game streaming support.

## Native HLS media adapter

This receiver includes a native HLS playback adapter and accepts a protocol v2 `stream_url` message from the paired phone.

Tizen uses Samsung AVPlay. webOS uses HTML5 video playback. The phone-side HLS publisher and real-device playback validation remain separate required integration steps.

<!-- PSLINK_WEBOS_DOCS_V1 -->
# PS Link LG webOS receiver

## Implementation

The webOS receiver uses HTML5 video for HLS playback and the PS Link
protocol client for subtitle communication.

The caption interface currently displays translated text in a text panel.
It is not a calibrated geometric overlay synchronized to video coordinates.

## Media limitations

The current phone HLS stream provides H.264 video only. Complete captured
game audio and verified audio/video synchronization are not established.

## Packaging and device validation

IPK packaging, signing, installation and physical LG TV testing remain
separate validation tasks. Do not treat source-level checks as proof that
a signed application works on a television.

Validate pairing, reconnection, subtitle delivery, HLS playback and
long-session stability on actual supported webOS hardware.
<!-- /PSLINK_WEBOS_DOCS_V1 -->
