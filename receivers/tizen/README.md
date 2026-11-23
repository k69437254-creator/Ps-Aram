# PS Link for Samsung Tizen TV

Tizen TV web application package source. Import this directory into Samsung
Tizen Studio as a TV Web Application and build/sign the WGT package using a
valid Samsung developer certificate.

Implemented:
- PS Link v2 WebSocket pairing.
- Six-digit pairing UI.
- Arabic subtitle and caption rendering.
- Disconnect handling and remote Escape-key disconnect.
- Shared PS Link transport client.

Not implemented:
- Compressed video decoding or audio playback.
- Native AVPlay media adapter.
- Automatic device discovery.
- Physical-device validation.

Media packets are detected but not rendered. This is subtitle receiver
support only, not complete game streaming support.

## Native HLS media adapter

This receiver includes a native HLS playback adapter and accepts a protocol v2 `stream_url` message from the paired phone.

Tizen uses Samsung AVPlay. webOS uses HTML5 video playback. The phone-side HLS publisher and real-device playback validation remain separate required integration steps.

<!-- PSLINK_TIZEN_DOCS_V1 -->
# PS Link Samsung Tizen receiver

## Implementation

The Tizen receiver uses Samsung AVPlay for HLS playback and the PS Link
protocol client for subtitle communication.

The caption interface currently displays translated text in a text panel.
It is not a calibrated geometric overlay synchronized to video coordinates.

## Media limitations

The current phone HLS stream provides H.264 video only. Complete captured
game audio and verified audio/video synchronization are not established.

## Packaging and device validation

WGT packaging, signing, installation and physical Samsung TV testing remain
separate validation tasks. Do not treat source-level checks as proof that
a signed application works on a television.

Validate pairing, reconnection, subtitle delivery, HLS playback and
long-session stability on actual supported Tizen hardware.
<!-- /PSLINK_TIZEN_DOCS_V1 -->
