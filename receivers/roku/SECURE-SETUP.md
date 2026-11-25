# Roku secure TLS proxy setup

This mode lets the Roku receiver connect to a trusted TLS reverse proxy instead
of talking directly to the phone's cleartext LAN endpoint. It does not encrypt
the proxy-to-phone hop; keep that hop on a trusted private network and do not
expose port 8765 publicly.

## Configuration

Edit `source/receiver-config.json` and set:

```json
{
  "phoneHost": "wss://receiver.example",
  "pairingCode": "123456"
}
```

`phoneHost` must be the trusted proxy origin using `wss://`, with no path,
query, credentials, or trailing route. The receiver uses:

- `wss://receiver.example/subtitle` for WebSocket pairing
- `https://receiver.example/receiver/roku/subtitle` for subtitle polling
- `https://receiver.example/hls/<token>/index.m3u8` for HLS playback

The code also retains legacy trusted-LAN mode: a bare host such as
`192.168.1.20` continues to use unencrypted `ws://` and `http://`. Prefer the
WSS mode outside an isolated trusted LAN.

## Reverse proxy example (Caddy)

```caddyfile
receiver.example {
    @websocket path /subtitle
    reverse_proxy @websocket 192.168.1.20:8765

    @hls path /hls/*
    reverse_proxy @hls 192.168.1.20:8765

    @roku_subtitles path /receiver/roku/subtitle
    reverse_proxy @roku_subtitles 192.168.1.20:8765
}
```

The proxy must preserve WebSocket upgrade headers. The phone currently
advertises a private-IP HTTP HLS URL; Roku rewrites only its validated `/hls/`
playlist path onto the configured HTTPS proxy origin. It rejects unexpected
query strings, fragments, and non-HLS paths.

The Roku client uses the platform CA bundle at
`common:/certs/ca-bundle.crt` for WSS, HTTPS subtitle polling, and HTTPS HLS.
Use a certificate whose hostname matches `receiver.example` and whose issuing
CA is trusted by the Roku device. Do not disable certificate validation.

## Security boundary

TLS protects Roku-to-proxy traffic. The example proxy connects to the phone
using plain HTTP/WS on the private LAN, so this is not end-to-end encryption.
For a stronger deployment, isolate that LAN segment or deploy a separately
validated TLS listener on the phone before routing proxy traffic to it.

## Validation boundary

The included automated contract test validates URL construction, secure-mode
selection, CA-bundle configuration, and HLS authority rewriting. It does not
compile BrightScript, verify the certificate on a physical Roku, validate
Caddy deployment, or prove real HLS playback. Those checks remain device and
operations validation tasks.
