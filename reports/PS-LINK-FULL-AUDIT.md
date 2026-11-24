# PS Link — تقرير تدقيق ملفات المستودع

- الفرع: `feature/platform-completion-pack`
- HEAD: `451feecc5162e8b2d7bbe66d6ae2c483464c5eef`
- تغييرات محلية غير ملتزمة: **نعم**
- الملفات المتتبعة: **190**
- الملفات غير المتتبعة وغير المستبعدة: **7**
- الملفات النصية المفهرسة: **189**
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
  - Phone-to-TV transport includes local unencrypted ws:// paths.

### Android TV (`android-tv`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - Manifest enables cleartext traffic for local ws:// transport; authenticated TLS transport is not implemented here.
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
  - AVPlay/HLS adapter exists, but complete playback/audio behavior is not proven on a supported Tizen device.
  - Vendor package build/sign/install and long-session A/V sync are not evidenced by this source audit.

### LG webOS (`webos`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - HTML media/HLS adapter exists, but complete playback/audio behavior is not proven on a supported webOS device.
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
  - Browser HLS playback exists, but device-level A/V synchronization and long-session stability are not proven.

### Web Browser (`web`)
- ملفات المصدر الموجودة: 4/4
- الحالة: **PARTIAL**
- النواقص:
  - The browser receiver has source and automated adapter tests, but browser/device HLS decoding, autoplay behavior, A/V synchronization and long-session stability are not proven.

### PlayStation via Chiaki-NG (`playstation`)
- ملفات المصدر الموجودة: 5/5
- الحالة: **PARTIAL**
- النواقص:
  - The supported route is Android Mobile + pinned Chiaki-NG Remote Play + phone-side PS Link relay; no native PS4/PS5 application is claimed.
  - Android CI must complete bootstrap and Gradle compilation, then the PS console/phone/TV path must be tested on hardware.

## العيوب والمخاطر المثبتة

- **HIGH / FIXED_IN_WORKTREE — PSL-001** `scripts/prepare_pslink_live_tv_streaming.py:91`: The overlay assumed fixed indentation and older caption-clear message shapes. Bootstrap failed first on the OCR timestamp and then on valid caption-clear calls carrying sessionId. The patch now accepts the current forms, preserves session IDs, and is idempotent. Evidence: GitHub Actions run 45481552752 failed before Gradle. Full bootstrap now succeeds on repeated local runs; focused overlay regression tests pass.
- **HIGH / OPEN — PSL-002** `mobile/src/main/AndroidManifest.xml:27`: Mobile and TV endpoints use ws:// and HTTP on the LAN, bind to all interfaces, and have no enforced pairing-attempt limit. The six-digit code is persisted, sent in cleartext, and is not encryption; same-network observers or active peers can attack the session. Evidence: Restrict use to a trusted LAN; do not expose port 8765. No end-to-end TLS/PAKE implementation is present.
- **HIGH / OPEN_LIMITATION — PSL-003** `mobile/src/main/java/com/pslink/mobile/capture/AacPlaybackCapture.kt:46`: The HLS AAC source is Android Playback Capture and depends on Android 10+, permission, and source-app capture policy. It is not the native Chiaki decoded-game-audio callback. Synthetic AAC tests verify muxing, not PS5 audio capture; device evidence is absent. Evidence: The separate Chiaki PCM WebSocket bridge is source-patched but Android CI did not reach compilation on this commit.
- **MEDIUM / PARTIALLY_TESTED — PSL-004** `mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt:535`: Arabic OCR now has three generated image samples covering font weight, size, background, and contrast. This is a narrow synthetic check, not game-scene accuracy or Android performance validation. Model provenance and redistribution terms still need confirmation. Evidence: Local Tesseract 5.5.4 sample test passed; physical-device OCR remains NOT TESTED.
- **MEDIUM / OPEN_EXTERNAL_VALIDATION — PSL-005** `platforms/targets.json:176`: No platform has complete acceptance evidence: APK/WGT/IPK/Roku packaging, installation, real pairing, audio/video, A/V sync, long-session stability, security, and physical-device evidence remain incomplete. Evidence: Acceptance matrix records 0/10 fully accepted platforms; browser and vendor receiver tests are simulated.
- **MEDIUM / INHERITED_PUBLIC_CLIENT_CREDENTIAL — PSL-006** `mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt:26`: The upstream native OAuth client embeds a client credential in the APK. It is extractable and must not be treated as a server-side secret. User passwords and account tokens are not intended to be persisted or logged. Evidence: Credential value intentionally omitted from the audit report.
- **LOW / FIXED_IN_WORKTREE — PSL-007** `scripts/verify-platform-completion-pack.sh:1`: Four shell scripts used a machine-specific Termux interpreter path and could not be launched directly on Linux. Their shebangs now use env bash. Evidence: All tracked shell scripts pass bash -n.
- **MEDIUM / FIXED_IN_WORKTREE — PSL-008** `tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt:116`: The Android TV receiver accepted v2 pairing but did not answer application-level heartbeat messages, causing compatible browser receivers to treat a live session as stalled. It now validates the protocol version and replies with HEARTBEAT_ACK. Evidence: The cross-platform regression suite and v2 protocol source tests pass; Android compilation remains blocked by the missing SDK.
- **HIGH / FIXED_IN_WORKTREE — PSL-009** `mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt:70`: The media sender used an untagged queue and could send stale packets through a replacement WebSocket; it also lacked bounded retry after transient disconnects. Queue entries now carry socket/generation identity, transitions are synchronized, and capped reconnect stops on manual disconnect. Evidence: Cross-platform regression tests and media-state source checks pass; Android/Kotlin compilation could not be run without an Android SDK.
- **MEDIUM / FIXED_IN_WORKTREE — PSL-010** `tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt:253`: A temporarily unavailable MediaCodec input buffer incorrectly forced the decoder to wait for a new keyframe, potentially freezing video until the next IDR. The transient no-buffer path now drops only that sample; actual invalid-buffer handling still requests keyframe recovery. Evidence: The focused regression contract passes; physical decoder behavior remains untested.

## GitHub Actions على HEAD

- [45481552752](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552752): **FAILURE** — Bootstrap failed before Gradle compilation; APK build steps were skipped.
- [45481552755](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552755): **PASS** — Tizen/webOS simulated receiver checks.
- [45481552764](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552764): **PASS** — Cross-platform regression checks.
- [45481552833](https://github.com/k69437254-creator/Ps-Aram/actions/runs/45481552833): **PASS** — Web receiver protocol and HLS adapter tests.

## فحوصات محلية

- `bash scripts/check-platform-regressions.sh` — **PASS**
- `python3 -m unittest tests.test_pslink_audit_contracts tests.test_v2_protocol_source_contracts -v` — **PASS (18 tests)**
- `python3 tests/test_arabic_ocr.py -v` — **PASS (3 synthetic Arabic samples; Tesseract 5.5.4)**
- `Node YAML parse of .github/workflows/*.yml; bash -n; git diff --check` — **PASS**
- `Gradle compilation and Android unit tests` — **NOT_RUN (Android SDK unavailable locally; current CI stopped in bootstrap)**
- `bash scripts/verify-hls-ts.sh` — **BLOCKED (integration playlists are generated by Android unit tests, which could not run without an Android SDK)**

## تفسير النتائج

- عدد الملفات ليس نسبة إنجاز.
- وجود ملفات المصدر لا يثبت اكتمال المسار الوظيفي.
- إعادة استخدام APK لا تثبت توافق Google TV أو Fire TV.
- نجاح CI أو توقيع APK أو تثبيته لا يثبت التشغيل الفعلي.
- تقرير JSON يتضمن بصمات SHA-256 لكل الملفات المتتبعة وغير المتتبعة غير المستبعدة، باستثناء تقريري التدقيق المولّدين لتجنب الإحالة الذاتية.
- يظل التدقيق الوظيفي الشامل لكل ملف، واختبار الأجهزة، وحسابات التوقيع متطلبات خارجية/متبقية.
