# PS Link Xbox Edge receiver

This is a browser receiver prototype for Microsoft Edge on Xbox.
It is NOT a native Xbox application or a Microsoft Store package.

Requirements:
- A reachable PS Link phone on the same local network.
- The phone receiver server running on TCP port 8765.
- A valid six-digit pairing code.
- A browser environment that permits the WebSocket connection.

Important:
- The current phone endpoint uses plain ws:// and http://.
- A page hosted over HTTPS may block these insecure connections.
- Production hosting requires a trusted HTTPS/WSS reverse proxy.
- HLS currently carries H.264 video only; complete audio support is not
  implemented or verified by this prototype.
- Physical Xbox Edge testing is required.
