# PS Link — تقرير تدقيق ملفات المستودع

- الفرع: `feature/platform-completion-pack`
- HEAD: `e6e988e3a93c9eaa03be40ea6243940fae964a9d`
- تغييرات محلية غير ملتزمة: **نعم**
- الملفات المتتبعة: **194**
- الملفات غير المتتبعة وغير المستبعدة: **8**
- الملفات النصية المفهرسة: **194**
- الملفات الثنائية المفهرسة: **8**
- ملفات متتبعة مفقودة من نسخة العمل: **0**
- المنصات المقبولة بالكامل وفق شروط القبول: **0/10 (0.0%)**

الجرد أدناه يسجل الوجود والنوع والحجم وSHA-256، ولا يدّعي مراجعة وظيفية فردية لكل ملف. هذا ليس اعتمادًا لأجهزة فعلية أو إعلان اكتمال 100%.

## المنصات العشر

### Android Mobile (`android-mobile`)
- ملفات المصدر الموجودة: 8/8
- الحالة: **PARTIAL**
- النواقص:
  - PSN account extraction is manual redirect-link handling, not silent OAuth.
  - Live OCR samples frames; Arabic OCR uses a bundled Tesseract model but accuracy is not validated on devices.
  - Long-duration A/V synchronization and device stability are not proven by source tests.
  - Phone-to-TV has local unencrypted ws:// and HTTP paths by default. Exact Origin allowlisting and trusted WSS endpoints are supported, but require deployment configuration and a trusted reverse proxy; the proxy-to-phone hop remains cleartext.

### Android TV (`android-tv`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - Manifest enables cleartext traffic for local ws:// transport. Exact Origins are enforced when supplied at build time; a TLS listener and certificate identity are not built into the app and require a trusted reverse proxy.
  - A/V synchronization and long-session decoder stability require more than structural/unit tests.

### Google TV (`google-tv`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - A separate Android APK module exists; device/emulator compatibility and target-specific behavior are not established by source presence.
  - Google TV codec, remote-control, and lifecycle compatibility remain unproven.

### Amazon Fire TV (`fire-tv`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - A separate Android APK module exists; Fire OS device compatibility is not established by source presence.
  - Fire TV codec, remote-control, and lifecycle compatibility remain unproven.

### Samsung Tizen (`tizen`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - AVPlay/HLS adapter and WSS-capable protocol client exist, but complete playback/audio behavior and packaged-app Origin behavior are not proven on a supported Tizen device.
  - Vendor package build/sign/install and long-session A/V sync are not evidenced by this source audit.

### LG webOS (`webos`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - HTML media/HLS adapter and WSS-capable protocol client exist, but complete playback/audio behavior and packaged-app Origin behavior are not proven on a supported webOS device.
  - IPK build/sign/install and long-session A/V sync are not evidenced by this source audit.

### Roku (`roku`)
- ملفات المصدر الموجودة: 4/4
- الحالة: **PARTIAL**
- النواقص:
  - SceneGraph/BrightScript HLS receiver is a prototype.
  - No complete audio pipeline or A/V synchronization implementation is evidenced by current Roku source.
  - Package build/sign/device validation are not evidenced by source presence.

### Xbox Edge (`xbox-edge`)
- ملفات المصدر الموجودة: 3/3
- الحالة: **PARTIAL**
- النواقص:
  - This is a browser receiver, not a native Xbox application.
  - WSS and same-origin HTTPS HLS proxy paths are supported; device-level A/V synchronization and long-session stability are not proven.

### Web Browser (`web`)
- ملفات المصدر الموجودة: 4/4
- الحالة: **PARTIAL**
- النواقص:
  - WSS and same-origin HTTPS HLS proxy paths are supported; browser/device HLS decoding, autoplay behavior, A/V synchronization and long-session stability are not proven.

### PlayStation via Chiaki-NG (`playstation`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - The supported route is Android Mobile + pinned Chiaki-NG Remote Play + phone-side PS Link relay; no native PS4/PS5 application is claimed.
  - Android CI must complete bootstrap and Gradle compilation, then the PS console/phone/TV path must be tested on hardware.

## العيوب والمخاطر المثبتة

- **HIGH / FIXED_IN_WORKTREE — PSL-001** `scripts/prepare_pslink_live_tv_streaming.py:91`: The overlay assumed fixed indentation and older caption-clear message shapes. Bootstrap failed first on the OCR timestamp and then on valid caption-clear calls carrying sessionId. The patch now accepts the current forms, preserves session IDs, and is idempotent. Evidence: GitHub Actions run 45481552752 failed before Gradle. Full bootstrap now succeeds on repeated local runs; focused overlay regression tests pass.
- **HIGH / OPEN_LIMITATION — PSL-002** `mobile/src/main/AndroidManifest.xml:27`: Phone and TV servers still bind cleartext ws:// and HTTP on the LAN by default; there is no in-app TLS listener or server certificate identity. Pairing attempts are throttled and browser Origins are checked against an exact build-time allowlist. Android and browser clients support trusted WSS endpoints through a reverse proxy, but the proxy-to-phone hop remains cleartext unless separately protected. Evidence: PairingAttemptLimiter applies to TV WebSocket auth and phone WebSocket/Roku HTTP auth. Exact-source success clears only that exact source; overflow-bucket success is intentionally a no-op, so hash collisions may cause collateral throttling but cannot clear accumulated failures. Overflow buckets are bounded by maxTrackedSources and expire under the rolling window. JVM regression smoke verifies collision isolation, alternating-source lockout, the map bound, and expiry. OriginAllowlist covers WebSocket, Roku pairing HTTP, and HLS routes. Do not expose port 8765 or claim end-to-end TLS.
- **HIGH / OPEN_LIMITATION — PSL-003** `mobile/src/main/java/com/pslink/mobile/capture/AacPlaybackCapture.kt:46`: The HLS AAC source is Android Playback Capture and depends on Android 10+, permission, and source-app capture policy. It is not the native Chiaki decoded-game-audio callback. Synthetic AAC tests verify muxing, not PS5 audio capture; device evidence is absent. Evidence: The separate Chiaki PCM bridge is source-patched but native audio capture is not demonstrated; prior CI failure was before APK creation and no PS5/device test is available.
- **MEDIUM / PARTIALLY_TESTED — PSL-004** `mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt:535`: Arabic OCR now has three generated image samples covering font weight, size, background, and contrast. This is a narrow synthetic check, not game-scene accuracy or Android performance validation. Model provenance and redistribution terms still need confirmation. Evidence: Local Tesseract 5.5.4 sample test passed; physical-device OCR remains NOT TESTED.
- **MEDIUM / OPEN_EXTERNAL_VALIDATION — PSL-005** `platforms/targets.json:176`: No platform has complete acceptance evidence: APK/WGT/IPK/Roku packaging, installation, real pairing, audio/video, A/V sync, long-session stability, security, and physical-device evidence remain incomplete. Evidence: Acceptance matrix records 0/10 fully accepted platforms; browser and vendor receiver tests are simulated.
- **MEDIUM / INHERITED_PUBLIC_CLIENT_CREDENTIAL — PSL-006** `mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt:26`: The upstream native OAuth client embeds a client credential in the APK. It is extractable and must not be treated as a server-side secret. User passwords and account tokens are not intended to be persisted or logged. Evidence: Credential value intentionally omitted from the audit report.
- **LOW / FIXED_IN_WORKTREE — PSL-007** `scripts/verify-platform-completion-pack.sh:1`: Four shell scripts used a machine-specific Termux interpreter path and could not be launched directly on Linux. Their shebangs now use env bash. Evidence: All tracked shell scripts pass bash -n.
- **MEDIUM / FIXED_IN_WORKTREE — PSL-008** `tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt:157`: The Android TV receiver accepted v2 pairing but did not answer application-level heartbeat messages, causing compatible browser receivers to treat a live session as stalled. It now validates the protocol version and replies with HEARTBEAT_ACK. Evidence: The cross-platform regression suite and v2 protocol source tests pass; Android compilation remains blocked by the missing SDK.
- **HIGH / FIXED_IN_WORKTREE — PSL-009** `mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt:74`: The media sender used an untagged queue and could send stale packets through a replacement WebSocket; it also lacked bounded retry after transient disconnects. Queue entries now carry socket/generation identity, transitions are synchronized, and capped reconnect stops on manual disconnect. Evidence: Cross-platform regression tests and media-state source checks pass; Android/Kotlin compilation could not be run without an Android SDK.
- **MEDIUM / FIXED_IN_WORKTREE — PSL-010** `tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt:253`: A temporarily unavailable MediaCodec input buffer incorrectly forced the decoder to wait for a new keyframe, potentially freezing video until the next IDR. The transient no-buffer path now drops only that sample; actual invalid-buffer handling still requests keyframe recovery. Evidence: The focused regression contract passes; physical decoder behavior remains untested.
- **HIGH / FIXED_IN_WORKTREE — PSL-011** `mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt:388`: The PixelCopy cancellation callback used a three-argument lambda incompatible with the resolved kotlinx.coroutines API, preventing mobile Kotlin compilation. It now accepts the Throwable callback parameter and still releases the bitmap on cancellation. Evidence: GitHub Actions run 45504723970 reported the type mismatch at this callback. The local compiler dependency is Android SDK/Gradle configuration, so the complete mobile build has not yet been rerun against this worktree.
- **HIGH / FIXED_IN_WORKTREE — PSL-012** `mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt:503`: Protocol v2 messages were not consistently bound to active sessions: mobile media_config lacked the ID, receiver servers accepted some stale controls/binary frames, and browser clients did not bind ACKs or heartbeats to their connection. The Android sender, three TV servers, phone server, and web/Tizen/webOS/Xbox clients now issue and enforce per-connection session IDs, including reconnect rebind and session-bound READY/ACK/stream URL messages. Evidence: Cross-platform JS lifecycle tests and Python source contracts verify authenticated session IDs, stale READY/stream rejection, media_config rebinding, server ACK identity, and active-socket binary guards. Android compile remains blocked locally by the missing SDK.
- **HIGH / FIXED_IN_WORKTREE — PSL-013** `mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt:78`: The phone-side WebSocket server authenticated browser receivers by pairing code but did not require a connection session ID; broadcasts were not scoped to the receiver connection. It now stores a per-socket session ID, rejects mismatched heartbeat frames, and tags READY, subtitle, and HLS URL messages for each recipient. Evidence: Python source contracts check session binding and per-client stream URL generation; browser tests validate READY, stream URL, subtitle, ACK, heartbeat, WSS, and HTTPS HLS routing.

## GitHub Actions على آخر HEAD مرفوع

لا توجد نتيجة workflow للتغييرات المحلية غير المرفوعة؛ النتائج أدناه تخص HEAD السابق المذكور في مصفوفة القبول.
- [45504723970](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723970): **FAILURE** — Gradle bootstrap passed; mobile Kotlin compilation failed on the PixelCopy cancellation callback and the not-yet-added sessionId field. APK build steps were not reached.
- [45504723896](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723896): **PASS** — Tizen/webOS simulated receiver checks.
- [45504723947](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723947): **PASS** — Cross-platform regression checks.
- [45504723980](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723980): **PASS** — Web receiver protocol and HLS adapter tests.

## فحوصات محلية

- `bash scripts/check-platform-regressions.sh` — **PASS (27 JavaScript syntax checks, receiver suites, JSON manifests, 10 targets, shell syntax, source validator, 23 Python contract tests)**
- `bash scripts/check-source.sh` — **PASS**
- `python3 tests/test_arabic_ocr.py -v` — **PASS (three bundled-model synthetic Arabic samples)**
- `kotlinc + JVM smoke tests for OriginAllowlist and PairingAttemptLimiter` — **PASS (collision success isolation, alternating-source lockout, maxTrackedSources bound, window expiry, Origin normalization; Gradle/JUnit runner unavailable because Android SDK is missing)**
- `python3 -m unittest discover -s tests -v` — **PASS (24 Python unit and source-contract tests)**
- `bash scripts/10-final-audit-runner.sh` — **PASS (9 audit scripts; 77 PASS, 6 WARN, 0 BLOCKER, 0 FAILED)**
- `Gradle Android tests/builds` — **BLOCKED locally: Android SDK is not installed; prior committed-HEAD run 45504723970 failed Kotlin compilation before any APK build; no CI run exists for these unpushed worktree changes**
- `git diff --check` — **PASS**

## تفسير النتائج

- عدد الملفات ليس نسبة إنجاز.
- وجود ملفات المصدر لا يثبت اكتمال المسار الوظيفي.
- إعادة استخدام APK لا تثبت توافق Google TV أو Fire TV.
- نجاح CI أو توقيع APK أو تثبيته لا يثبت التشغيل الفعلي.
- تقرير JSON يتضمن بصمات SHA-256 لكل الملفات المتتبعة وغير المتتبعة غير المستبعدة، باستثناء تقريري التدقيق المولّدين لتجنب الإحالة الذاتية.
- يظل التدقيق الوظيفي الشامل لكل ملف، واختبار الأجهزة، وحسابات التوقيع متطلبات خارجية/متبقية.
