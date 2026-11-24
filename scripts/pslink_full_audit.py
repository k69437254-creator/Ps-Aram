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
"android-mobile":["PSN account extraction is manual redirect-link handling, not silent OAuth.","Live OCR samples frames; Arabic OCR uses a bundled Tesseract model but accuracy is not validated on devices.","Long-duration A/V synchronization and device stability are not proven by source tests.","Phone-to-TV transport includes local unencrypted ws:// paths."],
"android-tv":["Manifest enables cleartext traffic for local ws:// transport; authenticated TLS transport is not implemented here.","A/V synchronization and long-session decoder stability require more than structural/unit tests."],
"google-tv":["A separate Android APK module exists; device/emulator compatibility and target-specific behavior are not established by source presence.","Google TV codec, remote-control, and lifecycle compatibility remain unproven."],
"fire-tv":["A separate Android APK module exists; Fire OS device compatibility is not established by source presence.","Fire TV codec, remote-control, and lifecycle compatibility remain unproven."],
"tizen":["AVPlay/HLS adapter exists, but complete playback/audio behavior is not proven on a supported Tizen device.","Vendor package build/sign/install and long-session A/V sync are not evidenced by this source audit."],
"webos":["HTML media/HLS adapter exists, but complete playback/audio behavior is not proven on a supported webOS device.","IPK build/sign/install and long-session A/V sync are not evidenced by this source audit."],
"roku":["SceneGraph/BrightScript HLS receiver is a prototype.","No complete audio pipeline or A/V synchronization implementation is evidenced by current Roku source.","Package build/sign/device validation are not evidenced by source presence."],
"xbox-edge":["This is a browser receiver, not a native Xbox application.","Browser HLS playback exists, but device-level A/V synchronization and long-session stability are not proven."],
"web":["The browser receiver has source and automated adapter tests, but browser/device HLS decoding, autoplay behavior, A/V synchronization and long-session stability are not proven."],
"playstation":["The supported route is Android Mobile + pinned Chiaki-NG Remote Play + phone-side PS Link relay; no native PS4/PS5 application is claimed.","Android CI must complete bootstrap and Gradle compilation, then the PS console/phone/TV path must be tested on hardware."]
}
GENERATED_REPORTS = {
    "reports/PS-LINK-FULL-AUDIT.md",
    "reports/PS-LINK-FULL-AUDIT.json",
}
def main():
    root=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path.cwd().resolve()
    if not (root/".git").exists(): raise SystemExit("ERROR: run inside the Git worktree or pass its path")
    branch=subprocess.check_output(["git","-C",str(root),"branch","--show-current"],text=True).strip()
    head=subprocess.check_output(["git","-C",str(root),"rev-parse","HEAD"],text=True).strip()
    raw=subprocess.check_output(["git","-C",str(root),"ls-files","-z"])
    tracked=[x.decode("utf-8","replace") for x in raw.split(b"\0") if x]
    inventory=[]; text_count=0; binary_count=0; missing=[]
    for rel in tracked:
        p=root/rel
        if not p.is_file(): missing.append(rel); continue
        data=p.read_bytes()
        is_text=b"\0" not in data[:8192]
        if is_text:
            try: data.decode("utf-8"); text_count+=1; kind="text"
            except UnicodeDecodeError: binary_count+=1; kind="binary"
        else: binary_count+=1; kind="binary"
        if rel not in GENERATED_REPORTS:
            inventory.append({"path":rel,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest(),"kind":kind})
    matrix={}
    for ident,name,required in TARGETS:
        absent=[p for p in required if not (root/p).is_file()]
        matrix[ident]={"name":name,"source_artifacts_present":len(required)-len(absent),"source_artifacts_expected":len(required),"missing_artifacts":absent,"source_status":"PARTIAL","known_gaps":GAPS[ident]}
    declared={}
    try:
        obj=json.loads((root/"platforms/targets.json").read_text(encoding="utf-8"))
        declared={x.get("id"):x.get("state") for x in obj.get("targets",[])}
    except Exception as exc: declared={"_parse_error":str(exc)}
    dirty=bool(subprocess.check_output(["git","-C",str(root),"status","--porcelain"],text=True).strip())
    report={"generated_utc":datetime.now(timezone.utc).isoformat(),"branch":branch,"head":head,"working_tree_dirty":dirty,"tracked_file_count":len(tracked),"text_file_count":text_count,"binary_file_count":binary_count,"missing_tracked_files":missing,"platforms":matrix,"declared_target_states":declared,"inventory_excluded_generated_reports":sorted(GENERATED_REPORTS),"inventory":inventory,"scope_note":"Source inventory/evidence audit only; inventory reflects the working tree and is not vendor-device certification or a 100% completion claim."}
    out=root/"reports"; out.mkdir(exist_ok=True)
    (out/"PS-LINK-FULL-AUDIT.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    lines=["# PS Link — تقرير تدقيق ملفات المستودع","",f"- الفرع: `{branch}`",f"- HEAD: `{head}`",f"- تغييرات محلية غير ملتزمة: **{'نعم' if dirty else 'لا'}**",f"- الملفات المتتبعة: **{len(tracked)}**",f"- الملفات النصية: **{text_count}**",f"- الملفات الثنائية: **{binary_count}**",f"- ملفات متتبعة مفقودة من نسخة العمل: **{len(missing)}**","","هذا تدقيق جرد وأدلة مصدر من نسخة العمل، وليس شهادة تشغيل على الأجهزة أو ادعاء اكتمال 100%.","","## المنصات العشر",""]
    for ident,item in matrix.items():
        lines += [f"### {item['name']} (`{ident}`)",f"- ملفات المصدر الموجودة: {item['source_artifacts_present']}/{item['source_artifacts_expected']}",f"- الحالة: **{item['source_status']}**"]
        if item["missing_artifacts"]: lines += ["- ملفات مفقودة:"]+[f"  - `{x}`" for x in item["missing_artifacts"]]
        lines += ["- النواقص:"]+[f"  - {x}" for x in item["known_gaps"]]+[""]
    if missing: lines += ["## ملفات متتبعة مفقودة",""]+[f"- `{x}`" for x in missing]
    lines += ["## تفسير النتائج","","- عدد الملفات ليس نسبة إنجاز.","- وجود ملفات المصدر لا يثبت اكتمال المسار الوظيفي.","- إعادة استخدام APK لا تثبت توافق Google TV أو Fire TV.","- نجاح CI أو توقيع APK أو تثبيته لا يدخل في قياس اكتمال ملفات المصدر.","- ملف JSON يتضمن SHA-256 للملفات المتتبعة باستثناء تقريري التدقيق المولّدين، لتجنب تضمين بصمات قديمة ذاتية الإحالة."]
    (out/"PS-LINK-FULL-AUDIT.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"PSLINK_AUDIT: branch={branch} head={head}")
    print(f"PSLINK_AUDIT: tracked={len(tracked)} text={text_count} binary={binary_count} missing={len(missing)}")
    print("PSLINK_AUDIT: reports/PS-LINK-FULL-AUDIT.md and .json")
    for k,v in matrix.items(): print(f"{k}: {v['source_status']} ({v['source_artifacts_present']}/{v['source_artifacts_expected']})")
if __name__=="__main__": main()
