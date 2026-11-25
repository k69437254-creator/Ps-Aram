#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
import hashlib, json, subprocess, sys
from pathlib import Path
from datetime import datetime, timezone

TARGETS = [
("android-mobile","Android Mobile",["mobile/build.gradle.kts","mobile/src/main/AndroidManifest.xml","mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt","mobile/src/main/assets/tessdata/ara.traineddata","mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt","mobile/src/main/java/com/pslink/mobile/capture/AacPlaybackCapture.kt","mobile/src/main/java/com/pslink/mobile/capture/HlsTransportStreamPublisher.kt","mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt"]),
("android-tv","Android TV",["tv/app/build.gradle.kts","tv/app/src/main/AndroidManifest.xml","tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt","tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt","tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt"]),
("google-tv","Google TV",["tv-google/app/build.gradle.kts","tv-google/app/src/main/AndroidManifest.xml","tv-google/app/src/main/java/com/pslink/tvgoogle/TvReceiverActivity.kt","tv-google/app/src/main/java/com/pslink/tvgoogle/TvMediaPlayer.kt","tv-google/app/src/main/java/com/pslink/tvgoogle/TvSubtitleServer.kt"]),
("fire-tv","Amazon Fire TV",["tv-fire/app/build.gradle.kts","tv-fire/app/src/main/AndroidManifest.xml","tv-fire/app/src/main/java/com/pslink/tvfire/TvReceiverActivity.kt","tv-fire/app/src/main/java/com/pslink/tvfire/TvMediaPlayer.kt","tv-fire/app/src/main/java/com/pslink/tvfire/TvSubtitleServer.kt"]),
("tizen","Samsung Tizen",["receivers/tizen/config.xml","receivers/tizen/index.html","receivers/tizen/app.js","receivers/tizen/pslink-client.js","receivers/tizen/media-player.js"]),
("webos","LG webOS",["receivers/webos/appinfo.json","receivers/webos/index.html","receivers/webos/app.js","receivers/webos/pslink-client.js","receivers/webos/media-player.js"]),
("roku","Roku",["receivers/roku/manifest","receivers/roku/source/main.brs","receivers/roku/source/MainScene.brs","receivers/roku/source/components/PairingTask.brs"]),
("xbox-edge","Xbox Edge",["receivers/xbox-edge/index.html","receivers/xbox-edge/app.js","receivers/xbox-edge/pslink-client.js"]),
("web","Web Browser",["receivers/web/index.html","receivers/web/app.js","receivers/web/pslink-client.js","receivers/web/media-player.js"]),
("playstation","PlayStation via Chiaki-NG",["mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt","mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt","mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt","scripts/prepare_pslink_live_tv_streaming.py","scripts/prepare_pslink_upstream.py"])
]
GAPS = {
"android-mobile":["PSN account extraction is manual redirect-link handling, not silent OAuth.","Live OCR samples frames; Arabic OCR uses a bundled Tesseract model but accuracy is not validated on devices.","Long-duration A/V synchronization and device stability are not proven by source tests.","Phone-to-TV has local unencrypted ws:// and HTTP paths by default. Exact Origin allowlisting and trusted WSS endpoints are supported, but require deployment configuration and a trusted reverse proxy; the proxy-to-phone hop remains cleartext."],
"android-tv":["Manifest enables cleartext traffic for local ws:// transport. Exact Origins are enforced when supplied at build time; a TLS listener and certificate identity are not built into the app and require a trusted reverse proxy.","A/V synchronization and long-session decoder stability require more than structural/unit tests."],
"google-tv":["A separate Android APK module exists; device/emulator compatibility and target-specific behavior are not established by source presence.","Google TV codec, remote-control, and lifecycle compatibility remain unproven."],
"fire-tv":["A separate Android APK module exists; Fire OS device compatibility is not established by source presence.","Fire TV codec, remote-control, and lifecycle compatibility remain unproven."],
"tizen":["AVPlay/HLS adapter and WSS-capable protocol client exist, but complete playback/audio behavior and packaged-app Origin behavior are not proven on a supported Tizen device.","Vendor package build/sign/install and long-session A/V sync are not evidenced by this source audit."],
"webos":["HTML media/HLS adapter and WSS-capable protocol client exist, but complete playback/audio behavior and packaged-app Origin behavior are not proven on a supported webOS device.","IPK build/sign/install and long-session A/V sync are not evidenced by this source audit."],
"roku":["SceneGraph/BrightScript HLS receiver is a prototype.","No complete audio pipeline or A/V synchronization implementation is evidenced by current Roku source.","Package build/sign/device validation are not evidenced by source presence."],
"xbox-edge":["This is a browser receiver, not a native Xbox application.","WSS and same-origin HTTPS HLS proxy paths are supported; device-level A/V synchronization and long-session stability are not proven."],
"web":["WSS and same-origin HTTPS HLS proxy paths are supported; browser/device HLS decoding, autoplay behavior, A/V synchronization and long-session stability are not proven."],
"playstation":["The supported route is Android Mobile + pinned Chiaki-NG Remote Play + phone-side PS Link relay; no native PS4/PS5 application is claimed.","Android CI must complete bootstrap and Gradle compilation, then the PS console/phone/TV path must be tested on hardware."]
}
GENERATED_REPORTS = {
    "reports/PS-LINK-FULL-AUDIT.md",
    "reports/PS-LINK-FULL-AUDIT.json",
}
FINDINGS = [
    {
        "id": "PSL-001",
        "severity": "HIGH",
        "state": "FIXED_IN_WORKTREE",
        "path": "scripts/prepare_pslink_live_tv_streaming.py",
        "needle": "def ensure_sample_timestamp",
        "summary": "The overlay assumed fixed indentation and older caption-clear message shapes. Bootstrap failed first on the OCR timestamp and then on valid caption-clear calls carrying sessionId. The patch now accepts the current forms, preserves session IDs, and is idempotent.",
        "evidence": "GitHub Actions run 45481552752 failed before Gradle. Full bootstrap now succeeds on repeated local runs; focused overlay regression tests pass.",
    },
    {
        "id": "PSL-002",
        "severity": "HIGH",
        "state": "OPEN_LIMITATION",
        "path": "mobile/src/main/AndroidManifest.xml",
        "needle": 'android:usesCleartextTraffic="true"',
        "summary": "Phone and TV servers still bind cleartext ws:// and HTTP on the LAN by default; there is no in-app TLS listener or server certificate identity. Pairing attempts are throttled and browser Origins are checked against an exact build-time allowlist. Android and browser clients support trusted WSS endpoints through a reverse proxy, but the proxy-to-phone hop remains cleartext unless separately protected.",
        "evidence": "PairingAttemptLimiter applies to TV WebSocket auth and phone WebSocket/Roku HTTP auth. Exact-source success clears only that exact source; overflow-bucket success is intentionally a no-op, so hash collisions may cause collateral throttling but cannot clear accumulated failures. Overflow buckets are bounded by maxTrackedSources and expire under the rolling window. JVM regression smoke verifies collision isolation, alternating-source lockout, the map bound, and expiry. OriginAllowlist covers WebSocket, Roku pairing HTTP, and HLS routes. Do not expose port 8765 or claim end-to-end TLS.",
    },
    {
        "id": "PSL-003",
        "severity": "HIGH",
        "state": "OPEN_LIMITATION",
        "path": "mobile/src/main/java/com/pslink/mobile/capture/AacPlaybackCapture.kt",
        "needle": "AudioPlaybackCaptureConfiguration.Builder(projection)",
        "summary": "The HLS AAC source is Android Playback Capture and depends on Android 10+, permission, and source-app capture policy. It is not the native Chiaki decoded-game-audio callback. Synthetic AAC tests verify muxing, not PS5 audio capture; device evidence is absent.",
        "evidence": "The separate Chiaki PCM bridge is source-patched but native audio capture is not demonstrated; prior CI failure was before APK creation and no PS5/device test is available.",
    },
    {
        "id": "PSL-004",
        "severity": "MEDIUM",
        "state": "PARTIALLY_TESTED",
        "path": "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt",
        "needle": "private fun ocrArabic",
        "summary": "Arabic OCR now has three generated image samples covering font weight, size, background, and contrast. This is a narrow synthetic check, not game-scene accuracy or Android performance validation. Model provenance and redistribution terms still need confirmation.",
        "evidence": "Local Tesseract 5.5.4 sample test passed; physical-device OCR remains NOT TESTED.",
    },
    {
        "id": "PSL-005",
        "severity": "MEDIUM",
        "state": "OPEN_EXTERNAL_VALIDATION",
        "path": "platforms/targets.json",
        "needle": '"acceptanceMatrixVersion"',
        "summary": "No platform has complete acceptance evidence: APK/WGT/IPK/Roku packaging, installation, real pairing, audio/video, A/V sync, long-session stability, security, and physical-device evidence remain incomplete.",
        "evidence": "Acceptance matrix records 0/10 fully accepted platforms; browser and vendor receiver tests are simulated.",
    },
    {
        "id": "PSL-006",
        "severity": "MEDIUM",
        "state": "INHERITED_PUBLIC_CLIENT_CREDENTIAL",
        "path": "mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt",
        "needle": "private const val CLIENT_SECRET",
        "summary": "The upstream native OAuth client embeds a client credential in the APK. It is extractable and must not be treated as a server-side secret. User passwords and account tokens are not intended to be persisted or logged.",
        "evidence": "Credential value intentionally omitted from the audit report.",
    },
    {
        "id": "PSL-007",
        "severity": "LOW",
        "state": "FIXED_IN_WORKTREE",
        "path": "scripts/verify-platform-completion-pack.sh",
        "needle": "#!/usr/bin/env bash",
        "summary": "Four shell scripts used a machine-specific Termux interpreter path and could not be launched directly on Linux. Their shebangs now use env bash.",
        "evidence": "All tracked shell scripts pass bash -n.",
    },
    {
        "id": "PSL-008",
        "severity": "MEDIUM",
        "state": "FIXED_IN_WORKTREE",
        "path": "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
        "needle": "PsLinkProtocol.KIND_HEARTBEAT_ACK",
        "summary": "The Android TV receiver accepted v2 pairing but did not answer application-level heartbeat messages, causing compatible browser receivers to treat a live session as stalled. It now validates the protocol version and replies with HEARTBEAT_ACK.",
        "evidence": "The cross-platform regression suite and v2 protocol source tests pass; Android compilation remains blocked by the missing SDK.",
    },
    {
        "id": "PSL-009",
        "severity": "HIGH",
        "state": "FIXED_IN_WORKTREE",
        "path": "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt",
        "needle": "private val mediaStateLock = java.lang.Object()",
        "summary": "The media sender used an untagged queue and could send stale packets through a replacement WebSocket; it also lacked bounded retry after transient disconnects. Queue entries now carry socket/generation identity, transitions are synchronized, and capped reconnect stops on manual disconnect.",
        "evidence": "Cross-platform regression tests and media-state source checks pass; Android/Kotlin compilation could not be run without an Android SDK.",
    },
    {
        "id": "PSL-010",
        "severity": "MEDIUM",
        "state": "FIXED_IN_WORKTREE",
        "path": "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt",
        "needle": "val inputIndex = codec.dequeueInputBuffer(10_000)",
        "summary": "A temporarily unavailable MediaCodec input buffer incorrectly forced the decoder to wait for a new keyframe, potentially freezing video until the next IDR. The transient no-buffer path now drops only that sample; actual invalid-buffer handling still requests keyframe recovery.",
        "evidence": "The focused regression contract passes; physical decoder behavior remains untested.",
    },
    {
        "id": "PSL-011",
        "severity": "HIGH",
        "state": "FIXED_IN_WORKTREE",
        "path": "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt",
        "needle": "continuation.resume(bitmap) { _ -> releaseBitmap() }",
        "summary": "The PixelCopy cancellation callback used a three-argument lambda incompatible with the resolved kotlinx.coroutines API, preventing mobile Kotlin compilation. It now accepts the Throwable callback parameter and still releases the bitmap on cancellation.",
        "evidence": "GitHub Actions run 45504723970 reported the type mismatch at this callback. The local compiler dependency is Android SDK/Gradle configuration, so the complete mobile build has not yet been rerun against this worktree.",
    },
    {
        "id": "PSL-012",
        "severity": "HIGH",
        "state": "FIXED_IN_WORKTREE",
        "path": "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt",
        "needle": '.put("sessionId", sessionId)',
        "summary": "Protocol v2 messages were not consistently bound to active sessions: mobile media_config lacked the ID, receiver servers accepted some stale controls/binary frames, and browser clients did not bind ACKs or heartbeats to their connection. The Android sender, three TV servers, phone server, and web/Tizen/webOS/Xbox clients now issue and enforce per-connection session IDs, including reconnect rebind and session-bound READY/ACK/stream URL messages.",
        "evidence": "Cross-platform JS lifecycle tests and Python source contracts verify authenticated session IDs, stale READY/stream rejection, media_config rebinding, server ACK identity, and active-socket binary guards. Android compile remains blocked locally by the missing SDK.",
    },
    {
        "id": "PSL-013",
        "severity": "HIGH",
        "state": "FIXED_IN_WORKTREE",
        "path": "mobile/src/main/java/com/pslink/mobile/capture/PhoneReceiverServer.kt",
        "needle": "private val sessions = ConcurrentHashMap<DefaultWebSocketServerSession, String>()",
        "summary": "The phone-side WebSocket server authenticated browser receivers by pairing code but did not require a connection session ID; broadcasts were not scoped to the receiver connection. It now stores a per-socket session ID, rejects mismatched heartbeat frames, and tags READY, subtitle, and HLS URL messages for each recipient.",
        "evidence": "Python source contracts check session binding and per-client stream URL generation; browser tests validate READY, stream URL, subtitle, ACK, heartbeat, WSS, and HTTPS HLS routing.",
    },
]
CI_EVIDENCE = [
    {
        "run_id": "45504723970",
        "result": "FAILURE",
        "stage": "Gradle bootstrap passed; mobile Kotlin compilation failed on the PixelCopy cancellation callback and the not-yet-added sessionId field. APK build steps were not reached.",
        "url": "https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723970",
    },
    {
        "run_id": "45504723896",
        "result": "PASS",
        "stage": "Tizen/webOS simulated receiver checks.",
        "url": "https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723896",
    },
    {
        "run_id": "45504723947",
        "result": "PASS",
        "stage": "Cross-platform regression checks.",
        "url": "https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723947",
    },
    {
        "run_id": "45504723980",
        "result": "PASS",
        "stage": "Web receiver protocol and HLS adapter tests.",
        "url": "https://github.com/k69437254-creator/Ps-Aram/actions/runs/45504723980",
    },
]
LOCAL_TEST_EVIDENCE = [
    {"command": "bash scripts/check-platform-regressions.sh", "result": "PASS (27 JavaScript syntax checks, receiver suites, JSON manifests, 10 targets, shell syntax, source validator, 23 Python contract tests)"},
    {"command": "bash scripts/check-source.sh", "result": "PASS"},
    {"command": "python3 tests/test_arabic_ocr.py -v", "result": "PASS (three bundled-model synthetic Arabic samples)"},
    {"command": "kotlinc + JVM smoke tests for OriginAllowlist and PairingAttemptLimiter", "result": "PASS (collision success isolation, alternating-source lockout, maxTrackedSources bound, window expiry, Origin normalization; Gradle/JUnit runner unavailable because Android SDK is missing)"},
    {"command": "python3 -m unittest discover -s tests -v", "result": "PASS (24 Python unit and source-contract tests)"},
    {"command": "bash scripts/10-final-audit-runner.sh", "result": "PASS (9 audit scripts; 77 PASS, 6 WARN, 0 BLOCKER, 0 FAILED)"},
    {"command": "Gradle Android tests/builds", "result": "BLOCKED locally: Android SDK is not installed; prior committed-HEAD run 45504723970 failed Kotlin compilation before any APK build; no CI run exists for these unpushed worktree changes"},
    {"command": "git diff --check", "result": "PASS"},
]


def source_reference(root: Path, path: str, needle: str) -> dict:
    lines = (root / path).read_text(encoding="utf-8").splitlines()
    line = next((index for index, value in enumerate(lines, 1) if needle in value), None)
    return {"path": path, "line": line}


def main():
    root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path.cwd().resolve()
    if not (root/".git").exists(): raise SystemExit("ERROR: run inside the Git worktree or pass its path")
    branch=subprocess.check_output(["git","-C",str(root),"branch","--show-current"],text=True).strip()
    head=subprocess.check_output(["git","-C",str(root),"rev-parse","HEAD"],text=True).strip()
    raw=subprocess.check_output(["git","-C",str(root),"ls-files","-z"])
    tracked=[x.decode("utf-8","replace") for x in raw.split(b"\0") if x]
    untracked_raw=subprocess.check_output(["git","-C",str(root),"ls-files","--others","--exclude-standard","-z"])
    untracked=[x.decode("utf-8","replace") for x in untracked_raw.split(b"\0") if x]
    inventory=[]; text_count=0; binary_count=0; missing=[]
    for rel in tracked + untracked:
        p=root/rel
        if not p.is_file():
            if rel in tracked: missing.append(rel)
            continue
        data=p.read_bytes()
        is_text=b"\0" not in data[:8192]
        if is_text:
            try: data.decode("utf-8"); text_count+=1; kind="text"
            except UnicodeDecodeError: binary_count+=1; kind="binary"
        else: binary_count+=1; kind="binary"
        if rel not in GENERATED_REPORTS:
            inventory.append({"path":rel,"tracked":rel in tracked,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest(),"kind":kind})
    matrix={}
    for ident,name,required in TARGETS:
        absent=[p for p in required if not (root/p).is_file()]
        matrix[ident]={"name":name,"source_artifacts_present":len(required)-len(absent),"source_artifacts_expected":len(required),"missing_artifacts":absent,"source_status":"PARTIAL","known_gaps":GAPS[ident]}
    declared={}
    acceptance_matrix={}
    try:
        obj=json.loads((root/"platforms/targets.json").read_text(encoding="utf-8"))
        declared={x.get("id"):x.get("state") for x in obj.get("targets",[])}
        acceptance_matrix=obj.get("acceptanceMatrix",{})
    except Exception as exc: declared={"_parse_error":str(exc)}
    dirty=bool(subprocess.check_output(["git","-C",str(root),"status","--porcelain"],text=True).strip())
    references=[{**item,**source_reference(root,item["path"],item["needle"])} for item in FINDINGS]
    fully_accepted=sum(
        1 for values in acceptance_matrix.values()
        if isinstance(values,dict) and all(
            values.get(key) in {"PASS","PASS_SIMULATED","PASS_ADAPTER_SIMULATION","NOT_APPLICABLE"}
            for key in ("sourceComplete","buildPassed","unitTestsPassed","integrationTestsPassed","packageVerified","installVerified","pairingPassed","videoPassed","audioPassed","avSyncPassed","longSessionStabilityPassed","securityReviewPassed","physicalDeviceEvidence")
        )
    )
    acceptance_percent=round(100*fully_accepted/10,1)
    report={"generated_utc":datetime.now(timezone.utc).isoformat(),"branch":branch,"head":head,"working_tree_dirty":dirty,"tracked_file_count":len(tracked),"untracked_file_count":len(untracked),"text_file_count":text_count,"binary_file_count":binary_count,"missing_tracked_files":missing,"platforms":matrix,"declared_target_states":declared,"acceptance_matrix":acceptance_matrix,"fully_accepted_platforms":fully_accepted,"verified_platform_completion_percent":acceptance_percent,"findings":references,"github_actions_evidence":CI_EVIDENCE,"local_test_evidence":LOCAL_TEST_EVIDENCE,"inventory_excluded_generated_reports":sorted(GENERATED_REPORTS),"inventory":inventory,"scope_note":"The inventory records existence, type, size, and SHA-256 for tracked and non-ignored untracked worktree files. It is not a per-file functional review or vendor-device certification; review findings and test evidence are separately recorded, and no 100% completion claim is made."}
    out=root/"reports"; out.mkdir(exist_ok=True)
    (out/"PS-LINK-FULL-AUDIT.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    lines=["# PS Link — تقرير تدقيق ملفات المستودع","",f"- الفرع: `{branch}`",f"- HEAD: `{head}`",f"- تغييرات محلية غير ملتزمة: **{'نعم' if dirty else 'لا'}**",f"- الملفات المتتبعة: **{len(tracked)}**",f"- الملفات غير المتتبعة وغير المستبعدة: **{len(untracked)}**",f"- الملفات النصية المفهرسة: **{text_count}**",f"- الملفات الثنائية المفهرسة: **{binary_count}**",f"- ملفات متتبعة مفقودة من نسخة العمل: **{len(missing)}**",f"- المنصات المقبولة بالكامل وفق شروط القبول: **{fully_accepted}/10 ({acceptance_percent}%)**","","الجرد أدناه يسجل الوجود والنوع والحجم وSHA-256، ولا يدّعي مراجعة وظيفية فردية لكل ملف. هذا ليس اعتمادًا لأجهزة فعلية أو إعلان اكتمال 100%.","","## المنصات العشر",""]
    for ident,item in matrix.items():
        lines += [f"### {item['name']} (`{ident}`)",f"- ملفات المصدر الموجودة: {item['source_artifacts_present']}/{item['source_artifacts_expected']}",f"- الحالة: **{item['source_status']}**"]
        if item["missing_artifacts"]: lines += ["- ملفات مفقودة:"]+[f"  - `{x}`" for x in item["missing_artifacts"]]
        lines += ["- النواقص:"]+[f"  - {x}" for x in item["known_gaps"]]+[""]
    if missing: lines += ["## ملفات متتبعة مفقودة",""]+[f"- `{x}`" for x in missing]
    lines += ["## العيوب والمخاطر المثبتة",""]
    for item in references:
        lines += [f"- **{item['severity']} / {item['state']} — {item['id']}** `{item['path']}:{item['line']}`: {item['summary']} Evidence: {item['evidence']}"]
    lines += ["","## GitHub Actions على آخر HEAD مرفوع","", "لا توجد نتيجة workflow للتغييرات المحلية غير المرفوعة؛ النتائج أدناه تخص HEAD السابق المذكور في مصفوفة القبول."]
    lines += [f"- [{item['run_id']}]({item['url']}): **{item['result']}** — {item['stage']}" for item in CI_EVIDENCE]
    lines += ["","## فحوصات محلية",""]
    lines += [f"- `{item['command']}` — **{item['result']}**" for item in LOCAL_TEST_EVIDENCE]
    lines += ["","## تفسير النتائج","","- عدد الملفات ليس نسبة إنجاز.","- وجود ملفات المصدر لا يثبت اكتمال المسار الوظيفي.","- إعادة استخدام APK لا تثبت توافق Google TV أو Fire TV.","- نجاح CI أو توقيع APK أو تثبيته لا يثبت التشغيل الفعلي.","- تقرير JSON يتضمن بصمات SHA-256 لكل الملفات المتتبعة وغير المتتبعة غير المستبعدة، باستثناء تقريري التدقيق المولّدين لتجنب الإحالة الذاتية.","- يظل التدقيق الوظيفي الشامل لكل ملف، واختبار الأجهزة، وحسابات التوقيع متطلبات خارجية/متبقية."]
    (out/"PS-LINK-FULL-AUDIT.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"PSLINK_AUDIT: branch={branch} head={head}")
    print(f"PSLINK_AUDIT: tracked={len(tracked)} text={text_count} binary={binary_count} missing={len(missing)}")
    print("PSLINK_AUDIT: reports/PS-LINK-FULL-AUDIT.md and .json")
    for k,v in matrix.items(): print(f"{k}: {v['source_status']} ({v['source_artifacts_present']}/{v['source_artifacts_expected']})")
if __name__=="__main__": main()
