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
