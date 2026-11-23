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

<!-- PSLINK_XBOX_EDGE_DOCS_V1 -->
# PS Link Xbox Edge receiver

## Scope

This is a browser-based receiver intended to run in Microsoft Edge on Xbox.
It is not a native Xbox application and has not been verified on physical
Xbox hardware by the automated tests in this repository.

## Connection requirements

The phone receiver uses port `8765` and exposes:

- WebSocket protocol endpoint: `/subtitle`
- HLS media endpoint: `/hls/*`

When the receiver page is served over HTTPS, its WebSocket connection must
use WSS. The browser must trust the TLS certificate presented by the host.

For a secure deployment, configure a reverse proxy to forward both
`/subtitle` (including WebSocket upgrades) and `/hls/*` to the phone
receiver on port `8765`.

Example templates are in `deploy/`:

- `deploy/Caddyfile.example`
- `deploy/env.example`

These are templates only. They do not configure DNS, certificates,
firewall rules, or a deployed server.

## Media limitations

The current HLS path carries H.264 video only. Complete captured game audio
and verified audio/video synchronization are not established.

## Testing

Run the simulated receiver tests from the repository root:

```bash
node receivers/xbox-edge/tests/receiver.test.js
```

These tests exercise source-level behavior with simulated inputs. They do
not prove network deployment, browser compatibility, or physical Xbox
operation.

## Device validation still required

- Verify that Xbox Edge trusts the deployment certificate.
- Pair with the phone receiver on the same intended network path.
- Verify live WebSocket connection and subtitle delivery.
- Verify HLS playback through the reverse proxy.
- Test reconnect behavior and a sustained session.
- Record device model, browser version, network conditions and test results.
<!-- /PSLINK_XBOX_EDGE_DOCS_V1 -->
