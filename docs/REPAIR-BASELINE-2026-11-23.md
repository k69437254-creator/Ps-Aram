# PS Link repair baseline — 2026-11-23

## Source identity

- Repository: `k69437254-creator/Ps-Aram`
- Branch: `feature/platform-completion-pack`
- Baseline commit: `4234e54248c56df56232ed10c39e755e3abe2806`
- Pinned Chiaki-NG revision remains `43728af2e639fecb8a7af1cd68c29820a40de973`.
- No upstream revision change is part of this repair.

## Confirmed CI baseline

- Android workflow run `45184813408`: shared core/controller tests and source audit passed; HLS verification failed before either APK build step; both APK build steps were skipped.
- Tizen/webOS workflow run `45184813367`: JS syntax checks, JSON/XML validation, and shared web protocol tests passed; platform-app smoke test failed at a byte-for-byte transport-client comparison.
- Browser receiver protocol workflow run `45184813457`: passed.

## HLS failure diagnosis

The uploaded CI evidence artifact was inspected. The AAC integration segment is a valid MPEG-TS program with H.264 PID `0x0100` (64×64) and AAC PID `0x0101` (48 kHz stereo). FFmpeg successfully decoded H.264 and AAC from that segment.

The failure came from test-output interference: multiple JUnit methods recursively deleted and reused the same `hls-integration-test` directory. The structural AAC test writes a deliberately synthetic, non-decodable AAC payload and happened to leave its short segment in the directory consumed by `verify-hls-ts.sh`. The verifier then auto-detected that very short TS segment as MPEG program stream. Explicit `-f mpegts` parsing identifies the actual PAT/PMT and H.264/AAC streams. The repair isolates each test's output and makes the verifier specify MPEG-TS explicitly when probing individual `.ts` segments. It intentionally leaves FFmpeg playlist input autodetection enabled for `.m3u8` files; an independent clean one-segment H.264 MPEG-TS HLS sample was separately decoded successfully with playlist autodetection; this is a demuxer-behavior check, not a rebuild of the repository publisher. Synthetic AAC remains confined to structural assertions and is not used as evidence of codec decode.

## Tizen/webOS failure diagnosis

The Tizen and webOS transport files are identical to each other, but intentionally differ from the generic browser client: they add HTTPS-to-WSS enforcement, WebSocket path validation, and HLS URL normalization for secure-page deployment. The old test compared whole source files byte-for-byte and therefore rejected the intended platform-specific security behavior. The replacement validates shared protocol/API contracts and runs client behavior against a fake WebSocket, including Arabic/Persian pairing digits, WSS enforcement, URL rejection/normalization, auth, config replay, subtitle ACK, stream URL, media packet parsing, and manual disconnect/reconnect cancellation.

## Local changes in this bundle

- Isolate the HLS integration, structural AAC, continuity-counter, and real-AAC test outputs.
- Force FFprobe to parse TS segments as MPEG-TS and verify H.264/AAC stream properties.
- Decode both video and audio from the real-AAC HLS playlist.
- Preserve the current PCM chunk while MediaCodec is under input-buffer backpressure; drain encoder output while retrying and fail/report after a one-second input-buffer stall rather than silently dropping the chunk.
- Replace brittle Tizen/webOS file equality with behavioral protocol/security tests.
- Expand target readiness fields so source, test, package, signature, install, device, full-media, A/V-sync, and long-run evidence remain distinct.

## Validation limits

The baseline CI artifact proves the real-AAC HLS segment can be probed and decoded in the CI environment. The repository source was inspected through the connected GitHub repository; a full local checkout/toolchain was not available in this execution environment. Therefore this bundle does not claim a fresh Kotlin compile, Gradle test run, APK build, WGT/IPK/Roku package build, package signing, physical-device validation, measured long-duration A/V drift, or production readiness. CI must be rerun after applying the patch. Physical vendor devices and their SDK/signing tools remain separate acceptance gates.
