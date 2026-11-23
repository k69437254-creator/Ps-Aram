#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
import hashlib, json, subprocess, sys
from pathlib import Path
from datetime import datetime, timezone

TARGETS = [
("android-mobile","Android Mobile",["mobile/build.gradle.kts","mobile/src/main/AndroidManifest.xml","mobile/src/main/java/com/pslink/mobile/capture/TvScreenStreamService.kt","mobile/src/main/java/com/pslink/mobile/capture/AacPlaybackCapture.kt","mobile/src/main/java/com/pslink/mobile/capture/HlsTransportStreamPublisher.kt","mobile/src/main/java/com/pslink/mobile/PsnAccountIdClient.kt"]),
("android-tv","Android TV",["tv/app/build.gradle.kts","tv/app/src/main/AndroidManifest.xml","tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt","tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt","tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt"]),
("google-tv","Google TV",["tv/app/build.gradle.kts","tv/app/src/main/AndroidManifest.xml","tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt"]),
("fire-tv","Amazon Fire TV",["tv/app/build.gradle.kts","tv/app/src/main/AndroidManifest.xml","tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt"]),
("tizen","Samsung Tizen",["receivers/tizen/config.xml","receivers/tizen/index.html","receivers/tizen/app.js","receivers/tizen/pslink-client.js","receivers/tizen/media-player.js"]),
("webos","LG webOS",["receivers/webos/appinfo.json","receivers/webos/index.html","receivers/webos/app.js","receivers/webos/pslink-client.js","receivers/webos/media-player.js"]),
("roku","Roku",["receivers/roku/manifest","receivers/roku/source/main.brs","receivers/roku/source/MainScene.brs","receivers/roku/source/components/PairingTask.brs"]),
("xbox-edge","Xbox Edge",["receivers/xbox-edge/index.html","receivers/xbox-edge/app.js","receivers/xbox-edge/pslink-client.js"])
]
GAPS = {
"android-mobile":["PSN account extraction is manual redirect-link handling, not silent OAuth.","Live OCR samples frames; Arabic OCR is not included.","Long-duration A/V synchronization and device stability are not proven by source tests.","Phone-to-TV transport includes local unencrypted ws:// paths."],
"android-tv":["Manifest enables cleartext traffic for local ws:// transport; authenticated TLS transport is not implemented here.","A/V synchronization and long-session decoder stability require more than structural/unit tests."],
"google-tv":["Reuses Android TV APK; no dedicated Google TV product flavor or target-specific compatibility implementation is evidenced.","Device/emulator compatibility is not established by source presence."],
"fire-tv":["Reuses Android TV APK; no dedicated Fire OS compatibility layer/product flavor is evidenced.","Fire TV codec, remote-control, and lifecycle compatibility remain unproven."],
"tizen":["AVPlay/HLS adapter exists, but complete playback/audio behavior is not proven on a supported Tizen device.","Vendor package build/sign/install and long-session A/V sync are not evidenced by this source audit."],
"webos":["HTML media/HLS adapter exists, but complete playback/audio behavior is not proven on a supported webOS device.","IPK build/sign/install and long-session A/V sync are not evidenced by this source audit."],
"roku":["SceneGraph/BrightScript HLS receiver is a prototype.","No complete audio pipeline or A/V synchronization implementation is evidenced by current Roku source.","Package build/sign/device validation are not evidenced by source presence."],
"xbox-edge":["This is a browser receiver, not a native Xbox application.","Browser HLS playback exists, but device-level A/V synchronization and long-session stability are not proven."]
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
    report={"generated_utc":datetime.now(timezone.utc).isoformat(),"branch":branch,"head":head,"tracked_file_count":len(tracked),"text_file_count":text_count,"binary_file_count":binary_count,"missing_tracked_files":missing,"platforms":matrix,"declared_target_states":declared,"inventory":inventory,"scope_note":"Source inventory/evidence audit only; not vendor-device certification or a 100% completion claim."}
    out=root/"reports"; out.mkdir(exist_ok=True)
    (out/"PS-LINK-FULL-AUDIT.json").write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    lines=["# PS Link — تقرير تدقيق ملفات المستودع","",f"- الفرع: `{branch}`",f"- HEAD: `{head}`",f"- الملفات المتتبعة: **{len(tracked)}**",f"- الملفات النصية: **{text_count}**",f"- الملفات الثنائية: **{binary_count}**",f"- ملفات متتبعة مفقودة من نسخة العمل: **{len(missing)}**","","هذا تدقيق جرد وأدلة مصدر، وليس شهادة تشغيل على الأجهزة أو ادعاء اكتمال 100%.","","## المنصات الثماني",""]
    for ident,item in matrix.items():
        lines += [f"### {item['name']} (`{ident}`)",f"- ملفات المصدر الموجودة: {item['source_artifacts_present']}/{item['source_artifacts_expected']}",f"- الحالة: **{item['source_status']}**"]
        if item["missing_artifacts"]: lines += ["- ملفات مفقودة:"]+[f"  - `{x}`" for x in item["missing_artifacts"]]
        lines += ["- النواقص:"]+[f"  - {x}" for x in item["known_gaps"]]+[""]
    if missing: lines += ["## ملفات متتبعة مفقودة",""]+[f"- `{x}`" for x in missing]
    lines += ["## تفسير النتائج","","- عدد الملفات ليس نسبة إنجاز.","- وجود ملفات المصدر لا يثبت اكتمال المسار الوظيفي.","- إعادة استخدام APK لا تثبت توافق Google TV أو Fire TV.","- نجاح CI أو توقيع APK أو تثبيته لا يدخل في قياس اكتمال ملفات المصدر.","- ملف JSON يتضمن SHA-256 لكل ملف متتبع لتوثيق نسخة التدقيق."]
    (out/"PS-LINK-FULL-AUDIT.md").write_text("\n".join(lines)+"\n",encoding="utf-8")
    print(f"PSLINK_AUDIT: branch={branch} head={head}")
    print(f"PSLINK_AUDIT: tracked={len(tracked)} text={text_count} binary={binary_count} missing={len(missing)}")
    print("PSLINK_AUDIT: reports/PS-LINK-FULL-AUDIT.md and .json")
    for k,v in matrix.items(): print(f"{k}: {v['source_status']} ({v['source_artifacts_present']}/{v['source_artifacts_expected']})")
if __name__=="__main__": main()
