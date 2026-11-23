<!-- PSLINK_DEVICE_VALIDATION_V1 -->
# PS Link — Device Validation Checklist

This checklist records real device validation. Source tests and simulated
network tests must not be reported as physical-device test results.

## 1. Phone and pairing

- [ ] Phone receiver starts successfully.
- [ ] Phone and receiver device use the intended network path.
- [ ] Pairing code is accepted.
- [ ] Invalid pairing code is rejected.
- [ ] Arabic and Persian pairing digits are normalized correctly.
- [ ] Session authentication completes.
- [ ] Disconnect and reconnect behavior is verified.
- [ ] Manual disconnect prevents automatic reconnection.

## 2. Subtitle and session lifecycle

- [ ] Subtitle reaches the receiver.
- [ ] Translation text and message identifiers are correct.
- [ ] Receiver acknowledgement is returned.
- [ ] Repeated messages do not produce unintended duplicates.
- [ ] Connection loss is handled safely.
- [ ] Reconnection works after network recovery.
- [ ] A sustained session is tested.
- [ ] No progressive memory, rendering or connection failure is observed.

## 3. Media playback

- [ ] HLS playlist is reachable from the receiver.
- [ ] HLS segments load successfully.
- [ ] H.264 video playback is verified.
- [ ] Playback recovery after temporary network interruption is tested.
- [ ] Video latency and stability are recorded.
- [ ] Audio capability is tested separately and not assumed.
- [ ] Audio/video synchronization is measured rather than assumed.

Current implementation limitation: the phone HLS path provides H.264 video.
Complete captured game audio and verified audio/video synchronization
are not established.

## 4. Platform-specific validation

### Android TV / Google TV

- [ ] Application installs and launches.
- [ ] Pairing and session lifecycle work.
- [ ] Subtitle display works.
- [ ] HLS playback works.
- [ ] Long-session stability is checked.

### Amazon Fire TV

- [ ] Application installs and launches on the tested Fire OS version.
- [ ] Pairing and subtitle delivery work.
- [ ] HLS playback works.
- [ ] Remote-control navigation works.
- [ ] Long-session stability is checked.

### Samsung Tizen

- [ ] WGT package is built and signed.
- [ ] Application installs and launches on the tested TV.
- [ ] AVPlay HLS playback works.
- [ ] Subtitle text panel displays correctly.
- [ ] Pairing and reconnection work.
- [ ] Long-session stability is checked.

### LG webOS

- [ ] IPK package is built and signed.
- [ ] Application installs and launches on the tested TV.
- [ ] HTML5 HLS playback works.
- [ ] Subtitle text panel displays correctly.
- [ ] Pairing and reconnection work.
- [ ] Long-session stability is checked.

### Roku

- [ ] Package is prepared and installed.
- [ ] Pairing works with the actual phone address and code.
- [ ] HLS video playback works.
- [ ] Subtitle polling and display work.
- [ ] Reconnection and long-session behavior are checked.

### Xbox Edge

- [ ] Receiver page opens in Xbox Edge.
- [ ] HTTPS certificate is trusted by the browser.
- [ ] WSS connection reaches the phone through the reverse proxy.
- [ ] `/subtitle` WebSocket upgrade works.
- [ ] `/hls/*` requests reach the phone receiver.
- [ ] Subtitle delivery and acknowledgements work.
- [ ] HLS video playback works.
- [ ] Reconnection and long-session behavior are checked.

Xbox Edge is a browser receiver, not a native Xbox application.

## 5. Evidence required for each physical test

Record the following for every tested device:

- Device manufacturer and exact model.
- Operating system and firmware version.
- Receiver application or browser version.
- Test date and network topology.
- Phone model and Android version.
- Pairing and session result.
- Subtitle and media playback result.
- Observed latency, interruptions and errors.
- Screenshots, logs or a short recording where appropriate.
- Tester conclusion: PASS, FAIL or NOT TESTED.

## 6. Reporting rules

- Automated source tests are not physical-device tests.
- A successful pairing handshake does not prove media playback.
- A visible subtitle does not prove calibrated video overlay placement.
- H.264 video playback does not prove game audio capture.
- Do not mark a platform fully validated while required device tests remain
  incomplete.
<!-- /PSLINK_DEVICE_VALIDATION_V1 -->
