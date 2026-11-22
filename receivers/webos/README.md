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
