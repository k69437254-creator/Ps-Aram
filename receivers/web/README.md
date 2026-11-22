# Browser TV receiver core

`pslink-client.js` implements the PS Link v2 WebSocket handshake,
six-digit pairing-code normalization, subtitle delivery and acknowledgement,
caption delivery, media configuration, and validation of the existing
28-byte PSLM binary packet header.

It is intended as a shared transport component for Tizen and webOS web apps.

It is not a complete TV application and does not decode or render compressed
video or PCM audio. Each platform still requires its own native media adapter,
application manifest/package, remote-control UI, lifecycle handling, and
physical-device validation.

## API

```javascript
var receiver = new PSLinkReceiver({
  onStatus: function (text) {},
  onReady: function (ready) {},
  onSubtitle: function (subtitle) {},
  onCaptions: function (captions) {},
  onMediaPacket: function (packet) {},
  onError: function (error) {}
});

receiver.connect("192.168.1.20", "123456");
receiver.configureMedia(1920, 1080, 0, 60);
receiver.disconnect();

The media packet payload is exposed as a "Uint8Array". The platform adapter
must determine whether it can decode the configured codec and present audio
and video using the supplied monotonic presentation timestamp.
