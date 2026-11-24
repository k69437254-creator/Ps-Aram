#!/usr/bin/env python3
"""PS Link live TV video/audio + Arabic caption replacement overlay.

Run from the PS-Link repository after scripts/bootstrap.sh has fetched the pinned
Chiaki-NG tree. This is deliberately idempotent and refuses ambiguous source anchors.
It installs a live encoded-video/decoded-PCM path over the existing paired WebSocket,
plus a MediaCodec/AudioTrack receiver on Android TV. It also folds in the earlier
caption timing/clear/geometry/static-check corrections.
"""
from __future__ import annotations

import re
import stat
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHI = ROOT / "third_party" / "chiaki-ng"
MOBILE = ROOT / "mobile/src/main/java/com/pslink/mobile"
TV = ROOT / "tv/app/src/main/java/com/pslink/tv"
UPSTREAM_SCRIPT = ROOT / "scripts/prepare_pslink_upstream.py"
MARKER = "PSLINK_LIVE_TV_STREAMING_V1"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Required file is missing: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".pslink-live-tmp")
    mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o644
    temporary.write_text(content, encoding="utf-8", newline="\n")
    temporary.chmod(mode)
    temporary.replace(path)


TRANSACTION_PATHS = (
    "third_party/chiaki-ng/android/app/src/main/cpp/video-decoder.h",
    "third_party/chiaki-ng/android/app/src/main/cpp/video-decoder.c",
    "third_party/chiaki-ng/android/app/src/main/cpp/chiaki-jni.c",
    "third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt",
    "third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt",
    "third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt",
    "mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt",
    "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt",
    "mobile/src/main/java/com/pslink/mobile/LiveCaptionOverlayView.kt",
    "tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt",
    "tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt",
    "tv/app/src/main/java/com/pslink/tv/TvCaptionOverlayView.kt",
    "tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt",
    "tv/app/src/main/res/layout/activity_tv_receiver.xml",
    "scripts/check-pslink-features.sh",
    "scripts/prepare_pslink_upstream.py",
)


def snapshot_transaction() -> dict[Path, tuple[bytes, int] | None]:
    state: dict[Path, tuple[bytes, int] | None] = {}
    for relative in TRANSACTION_PATHS:
        path = ROOT / relative
        state[path] = (path.read_bytes(), stat.S_IMODE(path.stat().st_mode)) if path.is_file() else None
    return state


def rollback_transaction(state: dict[Path, tuple[bytes, int] | None]) -> None:
    for path, snapshot in state.items():
        if snapshot is None:
            if path.exists():
                path.unlink()
            continue
        content, mode = snapshot
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + ".pslink-rollback-tmp")
        temporary.write_bytes(content)
        temporary.chmod(mode)
        temporary.replace(path)


def replace_once(src: str, old: str, new: str, label: str) -> str:
    if new in src:
        return src
    count = src.count(old)
    if count != 1:
        raise SystemExit(f"Anchor mismatch ({label}): expected exactly one match, found {count}")
    return src.replace(old, new, 1)


def ensure_sample_timestamp(src: str) -> str:
    timestamp = re.compile(r"(?m)^[ \t]*val samplePtsNs = System\.nanoTime\(\)$")
    timestamp_matches = list(timestamp.finditer(src))
    if timestamp_matches:
        if len(timestamp_matches) != 1:
            raise SystemExit(
                f"Expected one OCR sample timestamp, found {len(timestamp_matches)}"
            )
        return src

    anchor = re.compile(
        r"(?m)^(?P<indent>[ \t]*)val sampleStartedAt = SystemClock\.elapsedRealtime\(\)\n"
        r"(?P=indent)val bitmap = captureFrame\(\)$"
    )
    matches = list(anchor.finditer(src))
    if len(matches) != 1:
        raise SystemExit(
            "Anchor mismatch (timestamp the frame sampled for OCR): "
            f"expected exactly one match, found {len(matches)}"
        )
    match = matches[0]
    indent = match.group("indent")
    replacement = (
        f"{indent}val sampleStartedAt = SystemClock.elapsedRealtime()\n"
        f"{indent}val samplePtsNs = System.nanoTime()\n"
        f"{indent}val bitmap = captureFrame()"
    )
    return src[:match.start()] + replacement + src[match.end():]


def ensure_caption_clear_paths(src: str) -> str:
    for method, next_statement, label in (
        ("stop", "enabled = false", "clear TV captions when translation stops"),
        ("pause", "loop?.cancel()", "clear TV captions while game stream is paused"),
    ):
        existing = re.search(
            rf"(?m)^(?P<i>[ \t]*)fun {method}\(\) \{{\n(?P=i)    "
            r"tvClient\?\.sendCaptions\(emptyList\(\), System\.nanoTime\(\)(?:,\s*sessionId)?\)",
            src,
        )
        if existing:
            continue
        pattern = re.compile(
            rf"(?m)^(?P<i>[ \t]*)fun {method}\(\) \{{\n(?P=i)    "
            + re.escape(next_statement)
        )
        src, count = pattern.subn(
            lambda match: (
                f"{match.group('i')}fun {method}() {{\n"
                f"{match.group('i')}    tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)\n"
                f"{match.group('i')}    {next_statement}"
            ),
            src,
            count=1,
        )
        if count != 1:
            raise SystemExit(f"Anchor mismatch ({label}): found {count}")

    empty_clear = re.search(
        r"(?m)^[ \t]*lastSource = \"\"\n"
        r"(?P<i>[ \t]*)lastTranslation = \"\"\n"
        r"(?P=i)(?:stableTextGate\.reset\(\)\n(?P=i))?"
        r"onCaption\(emptyList\(\)\)\n"
        r"(?P=i)tvClient\?\.sendCaptions\(emptyList\(\), samplePtsNs(?:,\s*sessionId)?\)",
        src,
    )
    if not empty_clear:
        clear_pattern = re.compile(
            r"(?m)^(?P<i>[ \t]*)lastSource = \"\"\n(?P=i)lastTranslation = \"\"\n"
            r"(?P=i)onCaption\(emptyList\(\)\)$"
        )
        matches = list(clear_pattern.finditer(src))
        if not matches:
            raise SystemExit("Anchor mismatch (clear TV captions after empty OCR): found 0")
        match = matches[0]
        indent = match.group("i")
        replacement = (
            f'{indent}lastSource = ""\n{indent}lastTranslation = ""\n'
            f"{indent}onCaption(emptyList())\n"
            f"{indent}tvClient?.sendCaptions(emptyList(), samplePtsNs, sessionId)"
        )
        src = src[:match.start()] + replacement + src[match.end():]

    clear_packets = re.findall(
        r"tvClient\?\.sendCaptions\(emptyList\(\), samplePtsNs(?:,\s*sessionId)?\)",
        src,
    )
    if not clear_packets:
        raise SystemExit("Expected a timestamped empty-caption packet after empty OCR")
    return src


def ensure_caption_geometry_signature(src: str) -> str:
    signature = re.compile(
        r'(?m)^(?P<indent>[ \t]*)val signature = detected\.joinToString\("\|"\) \{\n'
        r'(?P=indent)    "(?P<expression>\$\{it\.source\}@[^"\n]+)"$'
    )
    matches = list(signature.finditer(src))
    if len(matches) != 1:
        raise SystemExit(
            "Anchor mismatch (caption geometry signature): "
            f"expected exactly one match, found {len(matches)}"
        )

    match = matches[0]
    expression = match.group("expression")
    if "it.left * 1000" in expression and "it.top * 1000" in expression \
            and "it.right * 1000" in expression and "it.bottom * 1000" in expression:
        return src
    if "it.left * 100" not in expression or "it.top * 100" not in expression:
        raise SystemExit("Unexpected caption geometry signature; refusing to overwrite it")

    indent = match.group("indent")
    replacement = (
        f'{indent}val signature = detected.joinToString("|") {{\n'
        f'{indent}    "${{it.source}}@${{(it.left * 1000).toInt()}},'
        f'${{(it.top * 1000).toInt()}},${{(it.right * 1000).toInt()}},'
        f'${{(it.bottom * 1000).toInt()}}"'
    )
    return src[:match.start()] + replacement + src[match.end():]


def function_bounds(src: str, pattern: str, label: str) -> tuple[int, int, int, int]:
    matches = list(re.finditer(pattern, src, re.S))
    if len(matches) != 1:
        raise SystemExit(f"Cannot locate {label}: expected one function, found {len(matches)}")
    start = matches[0].start()
    brace = src.find("{", matches[0].end())
    if brace < 0:
        raise SystemExit(f"Cannot locate body for {label}")
    depth = 0
    i = brace
    in_string = False
    in_char = False
    escaped = False
    line_comment = False
    block_comment = False
    while i < len(src):
        c = src[i]
        nxt = src[i + 1] if i + 1 < len(src) else ""
        if line_comment:
            if c == "\n":
                line_comment = False
        elif block_comment:
            if c == "*" and nxt == "/":
                block_comment = False
                i += 1
        elif in_string or in_char:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif in_string and c == '"':
                in_string = False
            elif in_char and c == "'":
                in_char = False
        else:
            if c == "/" and nxt == "/":
                line_comment = True
                i += 1
            elif c == "/" and nxt == "*":
                block_comment = True
                i += 1
            elif c == '"':
                in_string = True
            elif c == "'":
                in_char = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return start, brace, i, i + 1
        i += 1
    raise SystemExit(f"Unclosed function body while patching {label}")


def edit_c_function(src: str, pattern: str, label: str, transform) -> str:
    start, brace, close, end = function_bounds(src, pattern, label)
    body = src[brace + 1:close]
    updated = transform(body)
    if updated == body:
        return src
    return src[:brace + 1] + updated + src[close:]


# ---------------------------------------------------------------------------
# Native Chiaki callback bridge: encoded H.264/H.265 access units + decoded PCM.
# ---------------------------------------------------------------------------

def patch_video_decoder() -> None:
    header = CHI / "android/app/src/main/cpp/video-decoder.h"
    src = read(header)
    if "java_session_video_sample_meth" not in src:
        src = replace_once(
            src,
            "\tChiakiCodec target_codec;",
            "\tChiakiCodec target_codec;\n\tjobject java_session;\n\tjmethodID java_session_video_sample_meth;",
            "native video callback fields",
        )
    write(header, src)

    path = CHI / "android/app/src/main/cpp/video-decoder.c"
    src = read(path)
    if "static void pslink_live_dispatch_video_sample" not in src:
        include_anchor = '#include "video-decoder.h"\n'
        src = replace_once(src, include_anchor, include_anchor + '#include "chiaki-jni.h"\n\n#include <limits.h>\n#include <stdint.h>\n#include <time.h>\n', "video JNI/time includes")
        helper = r'''

/* PSLINK_LIVE_TV_STREAMING_V1 */
static uint64_t pslink_live_monotonic_ns(void)
{
    struct timespec ts;
    if(clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static bool pslink_live_is_key_frame(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size)
{
    for(size_t i = 0; i + 4 < size; ++i)
    {
        size_t nal = 0;
        if(data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1)
            nal = i + 3;
        else if(i + 4 < size && data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 0 && data[i + 3] == 1)
            nal = i + 4;
        if(!nal || nal >= size)
            continue;
        if(chiaki_codec_is_h265(decoder->target_codec))
        {
            unsigned type = (unsigned)((data[nal] >> 1) & 0x3f);
            if(type == 19 || type == 20 || type == 21)
                return true;
        }
        else if((data[nal] & 0x1f) == 5)
            return true;
    }
    return false;
}

static void pslink_live_dispatch_video_sample(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size)
{
    if(!decoder || !decoder->java_session || !decoder->java_session_video_sample_meth
            || !global_vm || !data || size == 0 || size > (size_t)INT_MAX)
        return;

    /* A decoder needs VPS/SPS/PPS or SPS/PPS after reconnect and key-frame recovery. */
    bool key_frame = pslink_live_is_key_frame(decoder, data, size);
    uint8_t *key_frame_with_csd = NULL;
    const uint8_t *send_data = data;
    size_t send_size = size;
    if(key_frame)
    {
        size_t csd0_size = 0;
        size_t csd1_size = 0;
        uint8_t *csd0 = NULL;
        uint8_t *csd1 = NULL;
        pthread_mutex_lock(&decoder->recording_mutex);
        csd0_size = decoder->recording_csd_size[0];
        csd1_size = decoder->recording_csd_size[1];
        if(csd0_size > 0 && decoder->recording_csd[0])
        {
            csd0 = malloc(csd0_size);
            if(csd0) memcpy(csd0, decoder->recording_csd[0], csd0_size);
        }
        if(csd1_size > 0 && decoder->recording_csd[1])
        {
            csd1 = malloc(csd1_size);
            if(csd1) memcpy(csd1, decoder->recording_csd[1], csd1_size);
        }
        pthread_mutex_unlock(&decoder->recording_mutex);

        if((csd0_size == 0 || csd0) && (csd1_size == 0 || csd1)
                && csd0_size <= SIZE_MAX - csd1_size
                && csd0_size + csd1_size <= SIZE_MAX - size
                && csd0_size + csd1_size + size <= (size_t)INT_MAX
                && csd0_size + csd1_size > 0)
        {
            key_frame_with_csd = malloc(csd0_size + csd1_size + size);
            if(key_frame_with_csd)
            {
                size_t offset = 0;
                if(csd0_size) { memcpy(key_frame_with_csd + offset, csd0, csd0_size); offset += csd0_size; }
                if(csd1_size) { memcpy(key_frame_with_csd + offset, csd1, csd1_size); offset += csd1_size; }
                memcpy(key_frame_with_csd + offset, data, size);
                send_data = key_frame_with_csd;
                send_size = offset + size;
            }
        }
        free(csd0);
        free(csd1);
    }

    JNIEnv *env = NULL;
    bool attached = false;
    jint status = (*global_vm)->GetEnv(global_vm, (void **)&env, JNI_VERSION_1_6);
    if(status == JNI_EDETACHED)
    {
        if((*global_vm)->AttachCurrentThread(global_vm, &env, NULL) != JNI_OK)
        {
            free(key_frame_with_csd);
            return;
        }
        attached = true;
    }
    else if(status != JNI_OK)
    {
        free(key_frame_with_csd);
        return;
    }

    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)send_size);
    if(bytes)
    {
        (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)send_size, (const jbyte *)send_data);
        if(!(*env)->ExceptionCheck(env))
        {
            (*env)->CallVoidMethod(env, decoder->java_session,
                    decoder->java_session_video_sample_meth,
                    bytes, (jlong)pslink_live_monotonic_ns(),
                    key_frame ? JNI_TRUE : JNI_FALSE);
        }
        (*env)->DeleteLocalRef(env, bytes);
    }
    if((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    if(attached)
        (*global_vm)->DetachCurrentThread(global_vm);
    free(key_frame_with_csd);
}
'''
        callback_anchor = "bool android_chiaki_video_decoder_video_sample("
        if callback_anchor not in src:
            raise SystemExit("Cannot find Chiaki encoded-video callback")
        src = src.replace(callback_anchor, helper + "\n" + callback_anchor, 1)

    def transform(body: str) -> str:
        if "pslink_live_dispatch_video_sample(decoder" in body:
            return body
        body = replace_once(
            body,
            "\tbool r = true;",
            "\tbool r = true;\n\tuint8_t *pslink_live_original_buf = buf;\n\tsize_t pslink_live_original_size = buf_size;\n\tbool pslink_live_decoder_ready = false;",
            "save video sample before MediaCodec consumes it",
        )
        body = replace_once(
            body,
            "\twhile(buf_size > 0)",
            "\tpslink_live_decoder_ready = decoder->codec != NULL;\n\twhile(buf_size > 0)",
            "mark video decoder availability",
        )
        tail = "\tchiaki_mutex_unlock(&decoder->codec_mutex);\n\treturn r;"
        if tail not in body:
            raise SystemExit("Cannot locate end of Chiaki video-sample callback")
        body = body.replace(
            tail,
            "\tchiaki_mutex_unlock(&decoder->codec_mutex);\n"
            "\tif(r && pslink_live_decoder_ready)\n"
            "\t{\n"
            "\t\tbool pslink_live_has_parameter_sets = false;\n"
            "\t\tpslink_analyze_access_unit(decoder, pslink_live_original_buf, pslink_live_original_size, &pslink_live_has_parameter_sets);\n"
            "\t\tif(pslink_live_has_parameter_sets)\n"
            "\t\t\tpslink_store_codec_config(decoder, pslink_live_original_buf, pslink_live_original_size);\n"
            "\t\tpslink_live_dispatch_video_sample(decoder, pslink_live_original_buf, pslink_live_original_size);\n"
            "\t}\n"
            "\treturn r;",
            1,
        )
        return body

    src = edit_c_function(src, r"bool\s+android_chiaki_video_decoder_video_sample\s*\(", "video sample callback", transform)
    write(path, src)


def patch_chiaki_session_bridge() -> None:
    kt = CHI / "android/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt"
    src = read(kt)
    if "PSLINK_LIVE_TV_STREAMING_V1" not in src:
        anchor = "\tvar eventCallback: ((event: Event) -> Unit)? = null\n"
        addition = '''	var eventCallback: ((event: Event) -> Unit)? = null
	@Volatile var videoSampleCallback: ((ByteArray, Long, Boolean) -> Unit)? = null
	@Volatile var audioSampleCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null

	/* PSLINK_LIVE_TV_STREAMING_V1 */
	private fun streamVideoSample(data: ByteArray, ptsNs: Long, keyFrame: Boolean)
	{
		videoSampleCallback?.invoke(data, ptsNs, keyFrame)
	}

	private fun streamAudioSample(data: ByteArray, sampleRate: Int, channels: Int, ptsNs: Long)
	{
		audioSampleCallback?.invoke(data, sampleRate, channels, ptsNs)
	}
'''
        src = replace_once(src, anchor, addition, "Chiaki Kotlin media callbacks")
    write(kt, src)

    session_file = CHI / "android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt"
    src = read(session_file)
    if "encodedVideoSampleCallback" not in src:
        anchor = "\tval state: LiveData<StreamState> get() = _state\n"
        addition = '''	val state: LiveData<StreamState> get() = _state
	@Volatile var encodedVideoSampleCallback: ((ByteArray, Long, Boolean) -> Unit)? = null
	@Volatile var decodedAudioSampleCallback: ((ByteArray, Int, Int, Long) -> Unit)? = null
'''
        src = replace_once(src, anchor, addition, "StreamSession callback properties")
        anchor = "\t\t\tval session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)\n"
        addition = '''			val session = Session(connectInfo, logManager.createNewFile().file.absolutePath, logVerbose)
			session.videoSampleCallback = { data, ptsNs, keyFrame -> encodedVideoSampleCallback?.invoke(data, ptsNs, keyFrame) }
			session.audioSampleCallback = { data, rate, channels, ptsNs -> decodedAudioSampleCallback?.invoke(data, rate, channels, ptsNs) }
'''
        src = replace_once(src, anchor, addition, "bind native media callbacks to StreamSession")
    write(session_file, src)

    jni = CHI / "android/app/src/main/cpp/chiaki-jni.c"
    src = read(jni)
    if "PSLINK_LIVE_TV_STREAMING_V1" not in src:
        src = replace_once(src, "#include <arpa/inet.h>\n", "#include <arpa/inet.h>\n#include <limits.h>\n#include <time.h>\n", "JNI time includes")
        src = replace_once(
            src,
            "\tAndroidChiakiAudioDecoder audio_decoder;\n",
            "\tAndroidChiakiAudioDecoder audio_decoder;\n\tjmethodID java_session_stream_audio_sample_meth;\n",
            "JNI audio method field",
        )
        helper = r'''

/* PSLINK_LIVE_TV_STREAMING_V1 */
static uint64_t pslink_live_audio_monotonic_ns(void)
{
    struct timespec ts;
    if(clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000000000ULL + (uint64_t)ts.tv_nsec;
}

static void pslink_live_dispatch_audio_sample(AndroidChiakiSession *session,
        const int16_t *samples, size_t samples_count)
{
    if(!session || !session->java_session || !session->java_session_stream_audio_sample_meth
            || !global_vm || !samples || samples_count == 0
            || samples_count > (size_t)INT_MAX / sizeof(int16_t))
        return;
    uint32_t rate = session->audio_decoder.audio_header.rate;
    uint32_t channels = session->audio_decoder.audio_header.channels;
    if(rate < 8000 || rate > 192000 || channels < 1 || channels > 2)
        return;

    JNIEnv *env = NULL;
    bool attached = false;
    jint status = (*global_vm)->GetEnv(global_vm, (void **)&env, JNI_VERSION_1_6);
    if(status == JNI_EDETACHED)
    {
        if((*global_vm)->AttachCurrentThread(global_vm, &env, NULL) != JNI_OK)
            return;
        attached = true;
    }
    else if(status != JNI_OK)
        return;

    jsize length = (jsize)(samples_count * sizeof(int16_t));
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if(bytes)
    {
        (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *)samples);
        if(!(*env)->ExceptionCheck(env))
            (*env)->CallVoidMethod(env, session->java_session,
                    session->java_session_stream_audio_sample_meth, bytes,
                    (jint)rate, (jint)channels, (jlong)pslink_live_audio_monotonic_ns());
        (*env)->DeleteLocalRef(env, bytes);
    }
    if((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
    if(attached)
        (*global_vm)->DetachCurrentThread(global_vm);
}
'''
        anchor = "} AndroidChiakiSession;\n"
        src = replace_once(src, anchor, anchor + helper, "native decoded-audio JNI callback")
        anchor = '\tsession->java_session_event_rumble_meth = E->GetMethodID(env, session->java_session_class, "eventRumble", "(II)V");\n'
        addition = '''	session->java_session_event_rumble_meth = E->GetMethodID(env, session->java_session_class, "eventRumble", "(II)V");
	session->video_decoder.java_session = session->java_session;
	session->video_decoder.java_session_video_sample_meth = E->GetMethodID(env, session->java_session_class, "streamVideoSample", "([BJZ)V");
	session->java_session_stream_audio_sample_meth = E->GetMethodID(env, session->java_session_class, "streamAudioSample", "([BIIJ)V");
'''
        src = replace_once(src, anchor, addition, "register native media callback method IDs")
        # prepare_pslink_audio_recording.py installs this callback and always invokes it
        # for decoded PCM; the MP4 recorder's own active flag remains independent.
        pattern = r"static void android_chiaki_audio_recording_frame\s*\([^)]*\)"
        def audio_body(body: str) -> str:
            needle = "android_chiaki_video_decoder_audio_sample(&session->video_decoder, samples, samples_count);"
            if "pslink_live_dispatch_audio_sample(session" in body:
                return body
            if body.count(needle) != 1:
                raise SystemExit("Expected native audio recorder callback from the existing PS Link audio overlay")
            return body.replace(needle, needle + "\n        pslink_live_dispatch_audio_sample(session, samples, samples_count);", 1)
        src = edit_c_function(src, pattern, "existing audio recorder callback", audio_body)
    write(jni, src)


# ---------------------------------------------------------------------------
# Phone-side WebSocket media sender and caption coordinate packets.
# ---------------------------------------------------------------------------

TV_CLIENT = r'''// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.os.Handler
import android.os.Looper
import com.pslink.core.PsLinkProtocol
import com.pslink.core.PairingCode
import com.pslink.core.SubtitlePacket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** LAN-only paired WebSocket: text control/captions plus bounded binary game media. */
class TvSubtitleClient(
    private val onStatus: (String, Boolean) -> Unit
) {
    companion object {
        private const val MEDIA_MAGIC = 0x50534C4D // PSLM
        private const val MEDIA_VERSION = 1
        private const val MEDIA_HEADER_BYTES = 28
        private const val MEDIA_VIDEO = 1
        private const val MEDIA_AUDIO_PCM16 = 2
        private const val FLAG_KEY_FRAME = 1
        private const val MAX_MEDIA_PACKET = 2 * 1024 * 1024
        private const val MAX_SOCKET_QUEUE_BYTES = 512L * 1024L
        private val MEDIA_CONFIG_SEQUENCE = AtomicLong(0L)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()

    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var mediaConfigJson: String? = null
@Volatile private var pendingMediaConfigId: String? = null
    // PSLINK_MEDIA_CONFIG_ACK_V1
    @Volatile private var mediaReady = false
    @Volatile private var terminalStatus: String? = null
    private val handshakeHandler = Handler(Looper.getMainLooper())
    private val handshakeLock = Any()
    @Volatile private var handshakeTimeoutSocket: WebSocket? = null
    private var handshakeTimeoutRunnable: Runnable? = null
    @Volatile private var awaitingKeyFrame = true
    private val pendingMessageIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mediaQueue = ArrayBlockingQueue<ByteArray>(32)

    private val mediaWorker = Thread({
        while (!Thread.currentThread().isInterrupted) {
            val packet = try { mediaQueue.take() } catch (_: InterruptedException) { break }
            val current = socket
            if (!ready || current == null) continue
            if (current.queueSize() > MAX_SOCKET_QUEUE_BYTES) {
                mediaQueue.clear()
                awaitingKeyFrame = true
                continue
            }
            if (!current.send(ByteString.of(*packet))) {
                mediaQueue.clear()
                awaitingKeyFrame = true
                onStatus("تعذر إرسال بث اللعبة إلى التلفاز", false)
            }
        }
    }, "PSLink-TV-Media-Sender").apply { isDaemon = true; start() }

    private fun cancelHandshakeTimeoutLocked(expectedSocket: WebSocket? = null) {
        if (expectedSocket != null && handshakeTimeoutSocket !== expectedSocket) return
        val pending = handshakeTimeoutRunnable
        handshakeTimeoutRunnable = null
        handshakeTimeoutSocket = null
        if (pending != null) handshakeHandler.removeCallbacks(pending)
    }

    private fun startHandshakeTimeout(webSocket: WebSocket) {
        lateinit var timeout: Runnable
        timeout = Runnable {
            val expired = synchronized(handshakeLock) {
                if (socket !== webSocket ||
                    handshakeTimeoutSocket !== webSocket ||
                    handshakeTimeoutRunnable !== timeout ||
                    ready ||
                    terminalStatus != null
                ) {
                    false
                } else {
                    handshakeTimeoutRunnable = null
                    handshakeTimeoutSocket = null
                    ready = false
                    mediaReady = false
                    mediaQueue.clear()
                    pendingMessageIds.clear()
                    socket = null
                    true
                }
            }
            if (expired) {
                onStatus("انتهت مهلة التحقق من رمز الاقتران؛ أعد المحاولة", false)
                webSocket.cancel()
            }
        }

        synchronized(handshakeLock) {
            if (socket === webSocket && !ready && terminalStatus == null) {
                handshakeTimeoutRunnable?.let {
                    handshakeHandler.removeCallbacks(it)
                }
                handshakeTimeoutSocket = webSocket
                handshakeTimeoutRunnable = timeout
                handshakeHandler.postDelayed(timeout, 10_000L)
            }
        }
    }

    fun connect(hostInput: String, pairingCode: String) {
        val host = hostInput.trim().removePrefix("ws://").removePrefix("http://").trimEnd('/')
        val normalizedCode = PairingCode.normalize(pairingCode)
        if (host.isBlank() || host.contains('/') || host.contains(' ')) {
            onStatus("أدخل عنوان IP صحيحًا للتلفاز", false)
            return
        }
        if (normalizedCode == null) {
            onStatus("رمز الاقتران يجب أن يتكون من 6 أرقام", false)
            return
        }
        disconnect()
        terminalStatus = null

        val request = runCatching {
            Request.Builder()
                .url("ws://$host:${PsLinkProtocol.PORT}${PsLinkProtocol.PATH}")
                .build()
        }.getOrElse {
            onStatus("عنوان التلفاز غير صالح؛ أدخل عنوان IP أو اسم مضيف صحيحًا", false)
            return
        }

        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) { webSocket.close(1000, "Superseded connection"); return }
                val sent = webSocket.send(PsLinkProtocol.auth(normalizedCode))
                if (!sent) {
                    terminalStatus = "تعذر إرسال رمز الاقتران"
                    onStatus(terminalStatus!!, false)
                    webSocket.cancel()
                } else {
                    onStatus("تم الاتصال؛ جارٍ التحقق من الرمز…", false)
                    startHandshakeTimeout(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("kind")) {
                    PsLinkProtocol.KIND_READY -> {
                        val accepted = synchronized(handshakeLock) {
                            if (socket !== webSocket) {
                                false
                            } else {
                                cancelHandshakeTimeoutLocked(webSocket)
                                ready = true
                                true
                            }
                        }
                        if (!accepted) return
                        mediaReady = false
                        awaitingKeyFrame = true
                        mediaQueue.clear()
                        val config = mediaConfigJson
                        if (config != null && !webSocket.send(config)) {
                            onStatus("تعذر إرسال إعدادات بث الوسائط إلى التلفاز", false)
                        }
                        onStatus("تم اقتران التلفاز؛ بانتظار تأكيد إعداد بث الوسائط", true)
                    }
                    "media_config_ack" -> {
                        val ackId = json.optString("requestId", "")
                        if (ackId.isNotBlank() && ackId == pendingMediaConfigId) {
                            mediaReady = true
                            onStatus("أكد التلفاز قبول إعدادات بث اللعبة؛ مسار الوسائط جاهز", true)
                        }
                    }
                    PsLinkProtocol.KIND_ACK -> {
                        val ackId = json.optString("id", "")
                        if (ackId.isNotBlank() && pendingMessageIds.remove(ackId))
                            onStatus("استلم التلفاز الترجمة", ready)
                    }
                    "error" -> {
                        val accepted = synchronized(handshakeLock) {
                            if (socket !== webSocket) {
                                false
                            } else {
                                cancelHandshakeTimeoutLocked(webSocket)
                                ready = false
                                mediaReady = false
                                terminalStatus = "رفض التلفاز الاقتران [${json.optString("errorCode", "UNKNOWN_AUTH_ERROR")}]: ${json.optString("message", "خطأ غير معروف")}"
                                true
                            }
                        }
                        if (!accepted) return
                        mediaQueue.clear()
                        pendingMessageIds.clear()
                        onStatus(terminalStatus!!, false)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val shouldReportFailure = synchronized(handshakeLock) {
                    if (socket !== webSocket) {
                        false
                    } else {
                        cancelHandshakeTimeoutLocked(webSocket)
                        socket = null
                        ready = false
                        mediaReady = false
                        pendingMessageIds.clear()
                        mediaQueue.clear()
                        val finalStatus = terminalStatus
                        terminalStatus = null
                        finalStatus == null
                    }
                }
                if (shouldReportFailure) {
                    onStatus("فشل الاتصال بالتلفاز: ${t.localizedMessage ?: "تحقق من الشبكة"}", false)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                val shouldReportDisconnect = synchronized(handshakeLock) {
                    if (socket !== webSocket) {
                        false
                    } else {
                        cancelHandshakeTimeoutLocked(webSocket)
                        socket = null
                        ready = false
                        mediaReady = false
                        pendingMessageIds.clear()
                        mediaQueue.clear()
                        val finalStatus = terminalStatus
                        terminalStatus = null
                        finalStatus == null
                    }
                }
                if (shouldReportDisconnect) onStatus("انقطع اتصال التلفاز", false)
            }
        })
    }

    fun configureMediaStream(width: Int, height: Int, codec: Int, fps: Int) {
        if (width !in 320..7680 || height !in 240..4320 || codec !in 0..2 || fps !in 15..120) {
            onStatus("إعدادات بث الفيديو غير صالحة", false)
            return
        }
        val requestId = "${System.nanoTime()}-${MEDIA_CONFIG_SEQUENCE.incrementAndGet()}"
        val config = JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "media_config")
            .put("requestId", requestId)
            .put("width", width)
            .put("height", height)
            .put("codec", codec)
            .put("fps", fps)
            .toString()

        mediaConfigJson = config
        pendingMediaConfigId = requestId
        mediaReady = false

        if (ready && socket?.send(config) != true) {
            onStatus("تعذر إرسال إعدادات بث الوسائط؛ أعد المحاولة", false)
        }
    }

    fun sendVideoSample(data: ByteArray, ptsNs: Long, keyFrame: Boolean) {
        if (!ready || !mediaReady) return
        if (data.isEmpty() || ptsNs <= 0L) return
        if (data.size > MAX_MEDIA_PACKET) {
            // A dropped access unit can break the H.264/H.265 reference chain.
            // Discard queued dependants and resume only from the next IDR/CRA.
            mediaQueue.clear()
            awaitingKeyFrame = true
            onStatus("إطار فيديو أكبر من الحد المسموح؛ جارٍ انتظار إطار مفتاح جديد", ready)
            return
        }
        if (awaitingKeyFrame && !keyFrame) return
        val packet = mediaPacket(MEDIA_VIDEO, if (keyFrame) FLAG_KEY_FRAME else 0, ptsNs, 0, 0, data)
        if (!mediaQueue.offer(packet)) {
            mediaQueue.clear()
            awaitingKeyFrame = true
            if (keyFrame && mediaQueue.offer(packet)) awaitingKeyFrame = false
            return
        }
        if (keyFrame) awaitingKeyFrame = false
    }

    fun sendAudioSample(data: ByteArray, sampleRate: Int, channels: Int, ptsNs: Long) {
        if (!ready || !mediaReady || data.isEmpty() || ptsNs <= 0L || data.size > 256 * 1024 || sampleRate !in 8000..192000 || channels !in 1..2) return
        if (data.size % (channels * 2) != 0) return
        val packet = mediaPacket(MEDIA_AUDIO_PCM16, 0, ptsNs, sampleRate, channels, data)
        // Audio is allowed to lose an occasional block under network pressure; it must
        // never block Chiaki's audio decoder thread.
        mediaQueue.offer(packet)
    }

    fun sendCaptions(lines: List<LiveGameTranslator.CaptionLine>, ptsNs: Long, sessionId: String = "") {
        if (!ready || ptsNs <= 0L) return
        val array = JSONArray()
        lines.take(3).forEach { line ->
            array.put(JSONObject()
                .put("text", line.translated.take(1000))
                .put("left", line.left.coerceIn(0f, 1f).toDouble())
                .put("top", line.top.coerceIn(0f, 1f).toDouble())
                .put("right", line.right.coerceIn(0f, 1f).toDouble())
                .put("bottom", line.bottom.coerceIn(0f, 1f).toDouble()))
        }
        socket?.send(JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "captions")
            .put("timestampNs", ptsNs)
            .put("sessionId", sessionId)
            .put("lines", array)
            .toString())
    }

    fun send(packet: SubtitlePacket): Boolean {
        if (!ready) {
            onStatus("اربط التلفاز أولًا", false)
            return false
        }
        if (pendingMessageIds.size >= 64) pendingMessageIds.clear()
        pendingMessageIds.add(packet.messageId)
        val sent = socket?.send(packet.toJson()) == true
        if (!sent) {
            pendingMessageIds.remove(packet.messageId)
            onStatus("تعذر إرسال الترجمة", false)
        }
        return sent
    }

    private fun mediaPacket(type: Int, flags: Int, ptsNs: Long, arg1: Int, arg2: Int, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(MEDIA_HEADER_BYTES + payload.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(MEDIA_MAGIC)
        buffer.put(MEDIA_VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putShort(flags.toShort())
        buffer.putLong(ptsNs)
        buffer.putInt(arg1)
        buffer.putInt(arg2)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    fun disconnect() {
        val previousSocket = synchronized(handshakeLock) {
            cancelHandshakeTimeoutLocked()
            ready = false
            mediaReady = false
            terminalStatus = null
            pendingMessageIds.clear()
            mediaQueue.clear()
            awaitingKeyFrame = true
            val previous = socket
            socket = null
            previous
        }
        previousSocket?.close(1000, "User disconnected")
    }

    fun close() {
        disconnect()
        mediaWorker.interrupt()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

'''


def patch_phone_translation() -> None:
    path = MOBILE / "TvSubtitleClient.kt"
    write(path, TV_CLIENT)

    translator_path = MOBILE / "LiveGameTranslator.kt"
    src = read(translator_path)
    src = replace_once(src, "private const val SAMPLE_INTERVAL_MS = 1_150L", "private const val SAMPLE_INTERVAL_MS = 650L", "live OCR sampling interval")
    src = replace_once(
        src,
        "private const val SAMPLE_INTERVAL_MS = 650L",
        "private const val SAMPLE_INTERVAL_MS = 650L\n        private const val MAX_OCR_CHARS = 160",
        "bound OCR text length",
    )
    # PSLINK_OCR_FILTER_IDEMPOTENT_V1
    dialogue_filter_is_current = (
        "sourceText.length > MAX_OCR_CHARS" in src
        and "rect.centerY() < bitmap.height * 0.48f" in src
        and "rect.width() < bitmap.width * 0.06f" in src
    )
    if not dialogue_filter_is_current:
        src = replace_once(
            src,
            """                                    if (sourceText.length < 2 ||
                                        rect.centerY() < bitmap.height * 0.60f ||
                                        rect.width() < bitmap.width * 0.10f ||
                                        rect.height() < 8
""",
            """                                    if (sourceText.length < 2 || sourceText.length > MAX_OCR_CHARS ||
                                        rect.centerY() < bitmap.height * 0.48f ||
                                        rect.width() < bitmap.width * 0.06f ||
                                        rect.height() < 8
""",
            "expand dialogue detection while rejecting small HUD text and OCR blocks",
        )
    src = ensure_sample_timestamp(src)
    src = ensure_caption_clear_paths(src)
    src = ensure_caption_geometry_signature(src)
    old_send = '''                                    if (captions.isNotEmpty()) {
                                        tvClient?.send(
                                            SubtitlePacket(
                                                game = "PS5 Remote Play",
                                                sourceText = "",
                                                translatedText = lastTranslation
                                            )
                                        )
                                    }'''
    new_send = '''                                    // The TV receives the exact OCR geometry so Arabic covers the
                                    // source dialogue in the streamed game image, not in a text panel.
                                    tvClient?.sendCaptions(captions, samplePtsNs)'''
    if not re.search(
        r"tvClient\?\.sendCaptions\(captions,\s*samplePtsNs(?:,\s*sessionId)?\)",
        src,
    ):
        src = replace_once(
            src,
            old_send,
            new_send.replace(
                "tvClient?.sendCaptions(captions, samplePtsNs)",
                "tvClient?.sendCaptions(captions, samplePtsNs, sessionId)",
            ),
            "send positioned Arabic captions to TV",
        )
    catch_clear = re.search(
        r"(?s)catch \(error: Exception\) \{\s*if \(generation == loopGeneration\) \{"
        r".*?tvClient\?\.sendCaptions\(emptyList\(\), System\.nanoTime\(\)(?:,\s*sessionId)?\)",
        src,
    )
    if not catch_clear:
        src = replace_once(
            src,
            '''                translator = null
                onStatus("توقفت الترجمة الحية: ${error.localizedMessage ?: \"تعذر معالجة إطار الفيديو\"}")''',
            '''                translator = null
                tvClient?.sendCaptions(emptyList(), System.nanoTime(), sessionId)
                onCaption(emptyList())
                onStatus("توقفت الترجمة الحية: ${error.localizedMessage ?: \"تعذر معالجة إطار الفيديو\"}")''',
            "clear TV captions after OCR failure",
        )
    # SubtitlePacket is no longer needed by this live translator.
    src = src.replace("import com.pslink.core.SubtitlePacket\n", "")
    write(translator_path, src)

    overlay = MOBILE / "LiveCaptionOverlayView.kt"
    src = read(overlay)
    src = replace_once(src, "alpha = 246", "alpha = 255", "fully mask source glyphs on phone")
    old_box = '''            // Expand around the source line so longer Arabic text fits and the English glyphs are hidden.
            val boxWidth = min(surfaceWidth * 0.90f, max(sourceRight - sourceLeft + 34f * density, surfaceWidth * 0.54f))
            val boxHeight = min(surfaceHeight * 0.18f, max(sourceBottom - sourceTop + 24f * density, 54f * density))'''
    new_box = '''            // Cover only the source glyphs, expanding for the measured Arabic line.
            val horizontalPadding = 16f * density
            val verticalPadding = 10f * density
            textPaint.textSize = 21f * density
            val maximumBoxWidth = surfaceWidth * 0.94f
            val sourceWidth = max(1f, sourceRight - sourceLeft)
            val translatedWidth = textPaint.measureText(line.translated)
            val boxWidth = min(
                maximumBoxWidth,
                max(sourceWidth + horizontalPadding * 2f, translatedWidth + horizontalPadding * 2f)
            )
            val boxHeight = min(
                surfaceHeight * 0.20f,
                max(sourceBottom - sourceTop + verticalPadding * 2f, 56f * density)
            )'''
    src = replace_once(src, old_box, new_box, "source replacement box geometry")
    old_text_layout = '''            val horizontalPadding = 28f * density
            val verticalPadding = 9f * density
            val contentWidth = (box.width() - horizontalPadding * 2f).toInt().coerceAtLeast(24)
            val availableHeight = box.height() - verticalPadding * 2f
            textPaint.textSize = min(24f * density, max(16f * density, availableHeight * 0.47f))'''
    new_text_layout = '''            val contentWidth = (box.width() - horizontalPadding * 2f).toInt().coerceAtLeast(24)
            val availableHeight = box.height() - verticalPadding * 2f
            textPaint.textSize = min(22f * density, max(15f * density, availableHeight * 0.36f))'''
    src = replace_once(src, old_text_layout, new_text_layout, "fit RTL Arabic text inside source mask")
    write(overlay, src)


# ---------------------------------------------------------------------------
# Android TV receiver: binary media decode + on-image Arabic replacement.
# ---------------------------------------------------------------------------

TV_SERVER = r'''// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import com.pslink.core.PsLinkProtocol
import com.pslink.core.PairingCode
import com.pslink.core.SubtitlePacket
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

data class TvCaptionLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class TvCaptionFrame(val timestampNs: Long, val lines: List<TvCaptionLine>)

class TvSubtitleServer(
    private val pairingCode: String,
    private val onMediaConfig: (TvMediaConfig) -> Unit,
    private val onMediaPacket: (ByteArray) -> Unit,
    private val onMediaStop: () -> Unit
) {
    private var engine: ApplicationEngine? = null
    private val activeSession = AtomicReference<Job?>(null)
    private val mutableLatestPacket = MutableStateFlow<SubtitlePacket?>(null)
    val latestPacket: StateFlow<SubtitlePacket?> = mutableLatestPacket.asStateFlow()
    private val mutableCaptions = MutableStateFlow<TvCaptionFrame?>(null)
    val latestCaptions: StateFlow<TvCaptionFrame?> = mutableCaptions.asStateFlow()

    @Synchronized fun start() {
        if (engine != null) return
        engine = embeddedServer(CIO, host = "0.0.0.0", port = PsLinkProtocol.PORT) {
            install(WebSockets)
            routing {
                webSocket(PsLinkProtocol.PATH) {
                    suspend fun rejectPairing(errorCode: String, message: String) {
                        send(Frame.Text(JSONObject()
                            .put("v", PsLinkProtocol.VERSION)
                            .put("kind", "error")
                            .put("errorCode", errorCode)
                            .put("message", message)
                            .toString()))
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, errorCode))
                    }

                    val authFrame = try {
                        withTimeoutOrNull(6_000L) { incoming.receive() }
                    } catch (_: ClosedReceiveChannelException) {
                        return@webSocket
                    }
                    if (authFrame == null) {
                        rejectPairing("AUTH_TIMEOUT", "انتهت مهلة إدخال رمز الاقتران؛ أعد المحاولة")
                        return@webSocket
                    }
                    if (authFrame !is Frame.Text) {
                        rejectPairing("INVALID_AUTH_FRAME", "طلب الاقتران غير صالح؛ أعد الاتصال من تطبيق الهاتف")
                        return@webSocket
                    }

                    val auth = runCatching { JSONObject(authFrame.readText()) }.getOrNull()
                    if (auth == null) {
                        rejectPairing("INVALID_AUTH_JSON", "تعذر قراءة بيانات الاقتران؛ حدّث التطبيق ثم أعد المحاولة")
                        return@webSocket
                    }
                    if (auth.optInt("v", -1) != PsLinkProtocol.VERSION) {
                        rejectPairing("PROTOCOL_MISMATCH", "إصدار بروتوكول الهاتف والتلفاز غير متوافق؛ حدّث التطبيقين")
                        return@webSocket
                    }
                    if (auth.optString("kind") != PsLinkProtocol.KIND_AUTH) {
                        rejectPairing("INVALID_AUTH_REQUEST", "نوع طلب الاقتران غير صالح؛ أعد الاتصال")
                        return@webSocket
                    }
                    val suppliedCode = PairingCode.normalize(auth.optString("code"))
                    val expectedCode = PairingCode.normalize(pairingCode)
                    if (suppliedCode == null || expectedCode == null || suppliedCode != expectedCode) {
                        rejectPairing("INVALID_PAIRING_CODE", "رمز الاقتران غير صحيح أو لا يخص هذا التلفاز")
                        return@webSocket
                    }
                    val sessionJob = coroutineContext[Job]
                        ?: throw IllegalStateException("Authenticated WebSocket session has no Job")
                    val previousSession = activeSession.getAndSet(sessionJob)
                    if (previousSession != null && previousSession !== sessionJob) {
                        // The new authenticated session replaces the old one.
                        previousSession.cancel()
                    }
                    try {
                        send(Frame.Text(JSONObject().put("v", PsLinkProtocol.VERSION).put("kind", PsLinkProtocol.KIND_READY).toString()))
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Binary -> {
                                    if (frame.data.size in 29..(2 * 1024 * 1024 + 28))
                                        onMediaPacket(frame.data)
                                }
                                is Frame.Text -> {
                                    val raw = frame.readText()
                                    handleTextFrame(raw)

                                    // PSLINK_MEDIA_CONFIG_ACK_V1
                                    mediaConfigAck(raw)?.let { acknowledgement ->
                                        send(Frame.Text(acknowledgement))
                                    }

                                    val packet = runCatching { SubtitlePacket.fromJson(raw) }.getOrNull()
                                    if (packet != null) {
                                        send(Frame.Text(JSONObject()
                                            .put("v", PsLinkProtocol.VERSION)
                                            .put("kind", PsLinkProtocol.KIND_ACK)
                                            .put("id", packet.messageId)
                                            .toString()))
                                    }
                                }
                                else -> Unit
                            }
                        }
                    } finally {
                        if (activeSession.compareAndSet(sessionJob, null)) {
                            mutableCaptions.value = TvCaptionFrame(0L, emptyList())
                            onMediaStop()
                        }
                    }
                }
            }
        }.start(wait = false)
    }

    // PSLINK_MEDIA_CONFIG_ACK_V1
    // Confirms only that the receiver accepted the configuration callback.
    // It does not claim that a hardware decoder has rendered a frame.
    private fun mediaConfigAck(raw: String): String? {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null

        if (json.optInt("v", -1) != PsLinkProtocol.VERSION ||
            json.optString("kind") != "media_config") {
            return null
        }

        val width = json.optInt("width", 0)
        val height = json.optInt("height", 0)
        val codec = json.optInt("codec", -1)
        val fps = json.optInt("fps", 60)
        val requestId = json.optString("requestId", "").take(100)

        if (requestId.isBlank() ||
            width !in 320..7680 ||
            height !in 240..4320 ||
            codec !in 0..2 ||
            fps !in 15..120) {
            return null
        }

        return JSONObject()
            .put("v", PsLinkProtocol.VERSION)
            .put("kind", "media_config_ack")
            .put("requestId", requestId)
            .toString()
    }

    private fun handleTextFrame(raw: String) {
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (json.optInt("v", -1) != PsLinkProtocol.VERSION) return
        when (json.optString("kind")) {
            "captions" -> {
                val array = json.optJSONArray("lines") ?: JSONArray()
                val lines = ArrayList<TvCaptionLine>(3)
                for (i in 0 until minOf(array.length(), 3)) {
                    val item = array.optJSONObject(i) ?: continue
                    val text = item.optString("text", "").take(1000).trim()
                    if (text.isBlank()) continue
                    val left = item.optDouble("left", -1.0).toFloat()
                    val top = item.optDouble("top", -1.0).toFloat()
                    val right = item.optDouble("right", -1.0).toFloat()
                    val bottom = item.optDouble("bottom", -1.0).toFloat()
                    if (left !in 0f..1f || top !in 0f..1f || right !in 0f..1f || bottom !in 0f..1f || right <= left || bottom <= top) continue
                    lines.add(TvCaptionLine(text, left, top, right, bottom))
                }
                mutableCaptions.value = TvCaptionFrame(
                    json.optLong("timestampNs", 0L).coerceAtLeast(0L),
                    lines
                )
            }
            "media_config" -> {
                val width = json.optInt("width", 0)
                val height = json.optInt("height", 0)
                val codec = json.optInt("codec", -1)
                val fps = json.optInt("fps", 60)
                if (width in 320..7680 && height in 240..4320 && codec in 0..2 && fps in 15..120)
                    onMediaConfig(TvMediaConfig(width, height, codec, fps))
            }
            PsLinkProtocol.KIND_SUBTITLE -> {
                val packet = runCatching { SubtitlePacket.fromJson(raw) }.getOrNull() ?: return
                mutableLatestPacket.value = packet
            }
        }
    }

    @Synchronized fun stop() {
        engine?.stop(gracePeriodMillis = 500L, timeoutMillis = 2_000L)
        engine = null
    }
}

'''

TV_PLAYER = r'''// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class TvMediaConfig(val width: Int, val height: Int, val codec: Int, val fps: Int)

/** Receives Chiaki's compressed video access units and PCM audio; no HDMI capture is involved. */
// PSLINK_LIVE_TV_PLAYOUT_V2
// Decode ahead and schedule output against the shared monotonic timeline.
class TvMediaPlayer(
    private val surfaceView: SurfaceView,
    private val onStatus: (String) -> Unit,
    private val onCaptions: (List<TvCaptionLine>) -> Unit
) : SurfaceHolder.Callback {
    companion object {
        private const val MAGIC = 0x50534C4D // PSLM
        private const val VERSION = 1
        private const val HEADER = 28
        private const val VIDEO = 1
        private const val AUDIO = 2
        private const val FLAG_KEY_FRAME = 1
        private const val VIDEO_QUEUE_CAPACITY = 96
        private const val AUDIO_QUEUE_CAPACITY = 80
        private const val PLAYOUT_BUFFER_NS = 1_000_000_000L
    }

    private data class VideoFrame(val data: ByteArray, val ptsNs: Long, val keyFrame: Boolean)
    private data class AudioBlock(val data: ByteArray, val ptsNs: Long, val rate: Int, val channels: Int)
    private data class QueuedCaption(val frame: TvCaptionFrame, val generation: Long)

    private val running = AtomicBoolean(true)
    private val videoQueue = ArrayBlockingQueue<VideoFrame>(VIDEO_QUEUE_CAPACITY)
    private val audioQueue = ArrayBlockingQueue<AudioBlock>(AUDIO_QUEUE_CAPACITY)
    private val captionQueue = ArrayBlockingQueue<QueuedCaption>(8)
    private val captionGeneration = AtomicLong(0L)
    @Volatile private var activeTrack: AudioTrack? = null
    @Volatile private var config: TvMediaConfig? = null
    @Volatile private var surface: Surface? = null
    @Volatile private var awaitingKeyFrame = true
    @Volatile private var senderOriginNs = 0L
    @Volatile private var localRenderOriginNs = 0L
    private var decoder: MediaCodec? = null
    private var activeConfig: TvMediaConfig? = null

    private val videoThread = Thread({ videoLoop() }, "PSLink-TV-Video-Decoder").apply { isDaemon = true }
    private val audioThread = Thread({ audioLoop() }, "PSLink-TV-Audio-Output").apply { isDaemon = true }
    private val captionThread = Thread({ captionLoop() }, "PSLink-TV-Caption-Scheduler").apply { isDaemon = true }

    init {
        surfaceView.holder.addCallback(this)
        videoThread.start()
        audioThread.start()
        captionThread.start()
    }

    fun resetStream() {
        config = null
        videoQueue.clear()
        audioQueue.clear()
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        activeTrack?.let { track -> runCatching { track.pause(); track.flush(); track.play() } }
        onCaptions(emptyList())
        awaitingKeyFrame = true
        senderOriginNs = 0L
        localRenderOriginNs = 0L
    }

    fun configure(next: TvMediaConfig) {
        config = next
        videoQueue.clear()
        audioQueue.clear()
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        activeTrack?.let { track -> runCatching { track.pause(); track.flush(); track.play() } }
        onCaptions(emptyList())
        awaitingKeyFrame = true
        senderOriginNs = 0L
        localRenderOriginNs = 0L
        onStatus("وصل إعداد بث اللعبة: ${next.width}×${next.height}، ${next.fps} إطار/ث")
    }

    /** Queue translated lines against the same monotonic timeline used by video and audio. */
    fun scheduleCaptions(frame: TvCaptionFrame) {
        if (!running.get() || frame.timestampNs <= 0L) return
        // Each text/clear is a timeline event. Do not cancel an earlier caption just
        // because the next OCR result arrived; both belong at different video PTS values.
        val queued = QueuedCaption(frame, captionGeneration.get())
        if (!captionQueue.offer(queued)) {
            // Under overload, invalidate the event currently waiting and retain the newest state.
            val generation = captionGeneration.incrementAndGet()
            captionQueue.clear()
            onCaptions(emptyList())
            captionQueue.offer(QueuedCaption(frame, generation))
        }
    }

    private fun awaitCaptionPlayout(queued: QueuedCaption): Boolean {
        val ptsNs = queued.frame.timestampNs
        if (ptsNs <= 0L) return false
        establishClock(ptsNs)
        val targetNs = localRenderOriginNs + (ptsNs - senderOriginNs).coerceAtLeast(0L)
        var waitNs = targetNs - System.nanoTime()
        while (waitNs > 0L && running.get() && queued.generation == captionGeneration.get()) {
            val sleepNs = minOf(waitNs, 20_000_000L)
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
            waitNs = targetNs - System.nanoTime()
        }
        return running.get() && queued.generation == captionGeneration.get()
    }

    private fun captionLoop() {
        try {
            while (running.get()) {
                val queued = captionQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (awaitCaptionPlayout(queued) && running.get()) onCaptions(queued.frame.lines)
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("تعذر مزامنة الترجمة على التلفاز: ${error.localizedMessage ?: "خطأ توقيت"}")
        }
    }

    /** Called by the WebSocket receiver. Binary packets are copied once into bounded queues. */
    fun submit(packet: ByteArray) {
        if (!running.get() || packet.size < HEADER + 1) return
        val input = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != VERSION) return
        val type = input.get().toInt() and 0xff
        val flags = input.short.toInt() and 0xffff
        val ptsNs = input.long
        if (ptsNs <= 0L) return
        val arg1 = input.int
        val arg2 = input.int
        val length = input.int
        if (length <= 0 || length != input.remaining()) return
        val payload = ByteArray(length)
        input.get(payload)
        when (type) {
            VIDEO -> {
                val keyFrame = flags and FLAG_KEY_FRAME != 0
                if (awaitingKeyFrame && !keyFrame) return
                if (!videoQueue.offer(VideoFrame(payload, ptsNs, keyFrame))) {
                    videoQueue.clear()
                    awaitingKeyFrame = true
                    if (keyFrame && videoQueue.offer(VideoFrame(payload, ptsNs, true))) awaitingKeyFrame = false
                    return
                }
                if (keyFrame) awaitingKeyFrame = false
            }
            AUDIO -> {
                if (arg1 !in 8000..192000 || arg2 !in 1..2 || length % (arg2 * 2) != 0) return
                if (!audioQueue.offer(AudioBlock(payload, ptsNs, arg1, arg2))) {
                    // Discard stale queued audio rather than playing seconds behind the game.
                    audioQueue.clear()
                    audioQueue.offer(AudioBlock(payload, ptsNs, arg1, arg2))
                }
            }
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surface = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surface = holder.surface
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surface = null
    }

    private fun establishClock(ptsNs: Long) {
        synchronized(this) {
            if (senderOriginNs == 0L) {
                senderOriginNs = ptsNs
                // Hold the compressed stream before decoding so OCR/translation has time
                // to return captions for the same frame timeline.
                localRenderOriginNs = System.nanoTime() + PLAYOUT_BUFFER_NS
            }
        }
    }

    private fun awaitPlayout(ptsNs: Long): Boolean {
        if (ptsNs <= 0L) return false
        establishClock(ptsNs)
        val targetNs = localRenderOriginNs + (ptsNs - senderOriginNs).coerceAtLeast(0L)
        var waitNs = targetNs - System.nanoTime()
        while (waitNs > 0L && running.get()) {
            val sleepNs = minOf(waitNs, 20_000_000L)
            Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt())
            waitNs = targetNs - System.nanoTime()
        }
        return running.get()
    }

    private fun videoLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get()) {
                val wanted = config
                val outputSurface = surface
                if (wanted == null || outputSurface == null || !outputSurface.isValid) {
                    if (outputSurface == null || wanted == null) releaseDecoder()
                    Thread.sleep(8)
                    continue
                }
                if (activeConfig != wanted || decoder == null) {
                    releaseDecoder()
                    try {
                        val mime = if (wanted.codec == 0) "video/avc" else "video/hevc"
                        val created = MediaCodec.createDecoderByType(mime)
                        val format = MediaFormat.createVideoFormat(mime, wanted.width, wanted.height)
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, wanted.fps)
                        created.configure(format, outputSurface, null, 0)
                        created.start()
                        decoder = created
                        activeConfig = wanted
                        awaitingKeyFrame = true
                        onStatus("مفكّك ترميز التلفاز جاهز؛ بانتظار إطار المفتاح")
                    } catch (error: Exception) {
                        releaseDecoder()
                        onStatus("تعذر تشغيل مفكّك فيديو التلفاز: ${error.localizedMessage ?: "Codec غير مدعوم"}")
                        Thread.sleep(1000)
                        continue
                    }
                }

                val frame = videoQueue.poll(30, TimeUnit.MILLISECONDS) ?: continue
                val codec = decoder ?: continue
                if (awaitingKeyFrame && !frame.keyFrame) continue
                if (frame.keyFrame) awaitingKeyFrame = false
                establishClock(frame.ptsNs)
                val origin = senderOriginNs
                val relativeUs = ((frame.ptsNs - origin).coerceAtLeast(0L)) / 1000L
                val inputIndex = codec.dequeueInputBuffer(10_000)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer == null || frame.data.size > inputBuffer.capacity()) {
                        codec.queueInputBuffer(inputIndex, 0, 0, relativeUs, 0)
                        awaitingKeyFrame = true
                    } else {
                        inputBuffer.clear()
                        inputBuffer.put(frame.data)
                        codec.queueInputBuffer(inputIndex, 0, frame.data.size, relativeUs, 0)
                    }
                } else {
                    awaitingKeyFrame = true
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(info, 0)
                    if (outputIndex >= 0) {
                        val renderAtNs = localRenderOriginNs + info.presentationTimeUs.coerceAtLeast(0L) * 1000L
                        codec.releaseOutputBuffer(outputIndex, maxOf(renderAtNs, System.nanoTime()))
                    } else {
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("توقف عرض فيديو التلفاز: ${error.localizedMessage ?: "خطأ في فك الترميز"}")
        } finally {
            releaseDecoder()
        }
    }

    private fun audioLoop() {
        var track: AudioTrack? = null
        var activeRate = 0
        var activeChannels = 0
        try {
            while (running.get()) {
                val block = audioQueue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (track == null || activeRate != block.rate || activeChannels != block.channels) {
                    track?.let { oldTrack -> runCatching { oldTrack.pause(); oldTrack.flush(); oldTrack.release() } }
                    val mask = if (block.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                    val minimum = AudioTrack.getMinBufferSize(block.rate, mask, AudioFormat.ENCODING_PCM_16BIT)
                    if (minimum <= 0) {
                        onStatus("تنسيق صوت اللعبة غير مدعوم على التلفاز")
                        track = null
                        continue
                    }
                    track = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                        .setAudioFormat(AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(block.rate)
                            .setChannelMask(mask)
                            .build())
                        .setBufferSizeInBytes(max(minimum, 4096))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    activeRate = block.rate
                    activeChannels = block.channels
                    activeTrack = track
                    track?.play()
                    onStatus("صوت اللعبة متصل بالتلفاز")
                }
                if (!awaitPlayout(block.ptsNs)) break
                var offset = 0
                while (offset < block.data.size && running.get()) {
                    val written = track?.write(block.data, offset, block.data.size - offset) ?: 0
                    if (written <= 0) break
                    offset += written
                }
            }
        } catch (_: InterruptedException) {
            // Normal shutdown.
        } catch (error: Exception) {
            onStatus("توقف صوت التلفاز: ${error.localizedMessage ?: "خطأ في تشغيل الصوت"}")
        } finally {
            track?.let { oldTrack -> runCatching { oldTrack.pause(); oldTrack.flush(); oldTrack.release() } }
            activeTrack = null
        }
    }

    private fun releaseDecoder() {
        val old = decoder
        decoder = null
        activeConfig = null
        if (old != null) {
            runCatching { old.stop() }
            runCatching { old.release() }
        }
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        surfaceView.holder.removeCallback(this)
        captionGeneration.incrementAndGet()
        captionQueue.clear()
        activeTrack?.let { track -> runCatching { track.pause(); track.flush() } }
        videoThread.interrupt()
        audioThread.interrupt()
        captionThread.interrupt()
    }
}
'''

TV_CAPTION_VIEW = r'''// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Draws Arabic in the OCR source bounds over the decoded PS5 image. */
class TvCaptionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var lines: List<TvCaptionLine> = emptyList()
    private val density = resources.displayMetrics.density
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; alpha = 255; style = Paint.Style.FILL }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 28f * density
        isSubpixelText = true
    }

    fun setCaptions(next: List<TvCaptionLine>) {
        lines = next.take(3)
        visibility = if (lines.isEmpty()) INVISIBLE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        lines.forEach { line ->
            val sourceLeft = line.left * width
            val sourceTop = line.top * height
            val sourceRight = line.right * width
            val sourceBottom = line.bottom * height
            val centerX = (sourceLeft + sourceRight) / 2f
            val centerY = (sourceTop + sourceBottom) / 2f
            val desired = text.measureText(line.text) + 52f * density
            val boxWidth = min(width * 0.92f, max(sourceRight - sourceLeft + 28f * density, desired))
            val boxHeight = min(height * 0.18f, max(sourceBottom - sourceTop + 24f * density, 54f * density))
            val box = RectF(
                (centerX - boxWidth / 2f).coerceAtLeast(4f * density),
                (centerY - boxHeight / 2f).coerceAtLeast(4f * density),
                (centerX + boxWidth / 2f).coerceAtMost(width - 4f * density),
                (centerY + boxHeight / 2f).coerceAtMost(height - 4f * density)
            )
            if (box.width() <= 0f || box.height() <= 0f) return@forEach
            canvas.drawRoundRect(box, 8f * density, 8f * density, background)
            val paddingX = 20f * density
            val contentWidth = (box.width() - paddingX * 2f).toInt().coerceAtLeast(24)
            text.textSize = min(29f * density, max(17f * density, box.height() * 0.48f))
            val layout = StaticLayout.Builder
                .obtain(line.text, 0, line.text.length, text, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            canvas.save()
            canvas.translate(box.left + paddingX, box.centerY() - layout.height / 2f)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
'''

TV_ACTIVITY = r'''// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.tv

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pslink.core.PsLinkProtocol
import com.pslink.tv.databinding.ActivityTvReceiverBinding
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Collections

class TvReceiverActivity : AppCompatActivity() {
    private lateinit var binding: ActivityTvReceiverBinding
    private lateinit var receiver: TvSubtitleServer
    private lateinit var mediaPlayer: TvMediaPlayer
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvReceiverBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        mediaPlayer = TvMediaPlayer(
            binding.gameSurfaceView,
            { status -> runOnUiThread { binding.serverStatusText.text = status } },
            { lines -> runOnUiThread { binding.captionOverlay.setCaptions(lines) } }
        )

        val preferences = getSharedPreferences("pslink_tv_receiver", MODE_PRIVATE)
        val code = preferences.getString("pair_code", null) ?:
            SecureRandom().nextInt(1_000_000).toString().padStart(6, '0').also {
                preferences.edit().putString("pair_code", it).commit()
            }
        binding.pairCodeText.text = code.chunked(3).joinToString(" ")
        binding.ipAddressText.text = localIpv4Addresses()

        receiver = TvSubtitleServer(
            code,
            onMediaConfig = { config ->
                mediaPlayer.configure(config)
                runOnUiThread {
                    binding.pairingPanel.visibility = View.GONE
                    binding.gameSurfaceView.visibility = View.VISIBLE
                    binding.captionOverlay.visibility = View.VISIBLE
                    binding.serverStatusText.text = "استُقبل بث اللعبة؛ جارٍ عرض الصورة والترجمة"
                }
            },
            onMediaPacket = mediaPlayer::submit,
            onMediaStop = {
                mediaPlayer.resetStream()
                runOnUiThread {
                    binding.captionOverlay.setCaptions(emptyList())
                    binding.gameSurfaceView.visibility = View.GONE
                    binding.pairingPanel.visibility = View.VISIBLE
                    binding.serverStatusText.text = "انقطع اتصال الهاتف؛ استخدم الرمز نفسه لإعادة الاقتران"
                }
            }
        )
        runCatching { receiver.start() }
            .onSuccess {
                binding.serverStatusText.text = "خادم PS Link جاهز على المنفذ ${PsLinkProtocol.PORT}"
                runCatching { registerDiscovery() }
                    .onFailure { binding.serverStatusText.text = "الخادم جاهز؛ استخدم عنوان IP ورمز الاقتران الظاهرين." }
            }
            .onFailure { error -> binding.serverStatusText.text = "تعذر تشغيل خادم الاستقبال: ${error.localizedMessage ?: "خطأ شبكة"}" }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    receiver.latestCaptions.collect { frame ->
                        if (frame != null) mediaPlayer.scheduleCaptions(frame)
                    }
                }
                launch {
                    receiver.latestPacket.collect { packet ->
                        if (packet != null) binding.subtitleText.text = packet.translatedText
                    }
                }
            }
        }
    }

    private fun registerDiscovery() {
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as android.net.wifi.WifiManager
        multicastLock = wifi.createMulticastLock("PSLinkNsdDiscovery").apply { setReferenceCounted(false); acquire() }
        val manager = getSystemService(NSD_SERVICE) as? NsdManager
            ?: throw IllegalStateException("Android NSD service is unavailable")
        nsdManager = manager
        val info = NsdServiceInfo().apply {
            serviceName = "PS-Link-TV-${android.os.Build.MODEL.take(12)}"
            serviceType = "_pslink._tcp."
            port = PsLinkProtocol.PORT
            setAttribute("v", PsLinkProtocol.VERSION.toString())
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                runOnUiThread { binding.serverStatusText.text = "التلفاز ظاهر تلقائيًا للهاتف على الشبكة المحلية" }
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { binding.serverStatusText.text = "الخادم يعمل؛ تعذر إعلان التلفاز تلقائيًا ($errorCode). استخدم IP الظاهر." }
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    private fun unregisterDiscovery() {
        registration?.let { listener -> runCatching { nsdManager?.unregisterService(listener) } }
        registration = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    override fun onDestroy() {
        unregisterDiscovery()
        if (::receiver.isInitialized) receiver.stop()
        if (::mediaPlayer.isInitialized) mediaPlayer.close()
        super.onDestroy()
    }

    private fun localIpv4Addresses(): String = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress }
            .mapNotNull { it.hostAddress }
            .distinct()
            .joinToString("   •   ")
            .ifBlank { "لم يُعثر على عنوان IPv4 للشبكة" }
    }.getOrElse { "تعذر قراءة عنوان الشبكة" }
}
'''

TV_LAYOUT = r'''<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <SurfaceView
        android:id="@+id/gameSurfaceView"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <com.pslink.tv.TvCaptionOverlayView
        android:id="@+id/captionOverlay"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:visibility="gone" />

    <LinearLayout
        android:id="@+id/pairingPanel"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:gravity="center"
        android:orientation="vertical"
        android:paddingStart="56dp"
        android:paddingTop="36dp"
        android:paddingEnd="56dp"
        android:paddingBottom="36dp"
        android:background="#050811">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:letterSpacing="0.16"
            android:text="PS LINK  /  TV"
            android:textColor="#5B9EFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:gravity="center"
            android:text="اللعبة على شاشتك، والترجمة فوق حوارها"
            android:textColor="#F4F7FC"
            android:textSize="32sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="10dp"
            android:gravity="center"
            android:text="اربط هاتف PS Link بالتلفاز على الشبكة المحلية نفسها. سيُرسل الهاتف صورة اللعبة وصوتها، ويعرض التلفاز العربية فوق موضع الحوار."
            android:textColor="#9AA9BD"
            android:textSize="17sp" />

        <com.google.android.material.card.MaterialCardView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="30dp"
            app:cardBackgroundColor="#101827"
            app:cardCornerRadius="24dp"
            app:strokeColor="#263A59"
            app:strokeWidth="1dp">
            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:gravity="center"
                android:orientation="vertical"
                android:padding="26dp">
                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="رمز الاقتران"
                    android:textColor="#9AA9BD"
                    android:textSize="15sp" />
                <TextView
                    android:id="@+id/pairCodeText"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="10dp"
                    android:fontFamily="monospace"
                    android:letterSpacing="0.18"
                    android:text="------"
                    android:textColor="#FFFFFF"
                    android:textSize="52sp"
                    android:textStyle="bold" />
                <TextView
                    android:id="@+id/ipAddressText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="14dp"
                    android:gravity="center"
                    android:text="عنوان الشبكة سيظهر هنا"
                    android:textColor="#5B9EFF"
                    android:textSize="18sp" />
                <TextView
                    android:id="@+id/serverStatusText"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="12dp"
                    android:gravity="center"
                    android:text="بدء خادم الاستقبال…"
                    android:textColor="#9AA9BD"
                    android:textSize="13sp" />
            </LinearLayout>
        </com.google.android.material.card.MaterialCardView>

        <TextView
            android:id="@+id/subtitleText"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="16dp"
            android:gravity="center"
            android:text="بانتظار بث الهاتف…"
            android:textColor="#FFFFFF"
            android:textSize="16sp" />
    </LinearLayout>
</FrameLayout>
'''


def patch_tv_app() -> None:
    write(TV / "TvSubtitleServer.kt", TV_SERVER)
    write(TV / "TvMediaPlayer.kt", TV_PLAYER)
    write(TV / "TvCaptionOverlayView.kt", TV_CAPTION_VIEW)
    write(TV / "TvReceiverActivity.kt", TV_ACTIVITY)
    write(ROOT / "tv/app/src/main/res/layout/activity_tv_receiver.xml", TV_LAYOUT)


def patch_stream_activity_wiring() -> None:
    path = CHI / "android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
    src = read(path)
    if "configureMediaStream(profile.width" not in src:
        anchor = "\t\tviewModel.session.resume()\n\t\tpsLinkTranslator?.resume()"
        addition = '''		val profile = viewModel.session.connectInfo.videoProfile
		psLinkTvClient?.configureMediaStream(profile.width, profile.height, profile.codec.value, profile.maxFPS)
		viewModel.session.resume()
		psLinkTranslator?.resume()'''
        src = replace_once(src, anchor, addition, "start TV video configuration before native stream resumes")
    if "decodedAudioSampleCallback" not in src:
        anchor = "\t\tpsLinkTranslator = LiveGameTranslator(this, binding.surfaceView, psLinkTvClient,"
        wiring = '''		viewModel.session.encodedVideoSampleCallback = { data, ptsNs, keyFrame ->
			psLinkTvClient?.sendVideoSample(data, ptsNs, keyFrame)
		}
		viewModel.session.decodedAudioSampleCallback = { data, sampleRate, channels, ptsNs ->
			psLinkTvClient?.sendAudioSample(data, sampleRate, channels, ptsNs)
		}

'''
        src = replace_once(src, anchor, wiring + anchor, "connect native Chiaki media samples to paired TV client")
    # Ensure callbacks do not retain the Activity after its destruction.
    src = replace_once(
        src,
        "\t\tpsLinkTranslator?.close()\n\t\tpsLinkTvClient?.close()",
        "\t\tpsLinkTranslator?.close()\n\t\tviewModel.session.encodedVideoSampleCallback = null\n\t\tviewModel.session.decodedAudioSampleCallback = null\n\t\tpsLinkTvClient?.close()",
        "release TV media callback references",
    )
    write(path, src)


def patch_static_checks() -> None:
    path = ROOT / "scripts/check-pslink-features.sh"
    src = read(path)
    src = src.replace("need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'newTranslator.translate(text).awaitResult()'",
                      "need mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt 'newTranslator.translate(line.source).awaitResult()'")
    src = src.replace("need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'pendingMessageId'",
                      "need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'pendingMessageIds'")
    src = src.replace('bash -n "$ROOT/scripts/verify-apk.sh" "$ROOT/scripts/diagnose-apk-termux.sh"',
                      'bash -n "$ROOT/scripts/verify-apk.sh"\nif [[ -f "$ROOT/scripts/diagnose-apk-termux.sh" ]]; then bash -n "$ROOT/scripts/diagnose-apk-termux.sh"; fi')
    src = src.replace('"tv/app/src/main/res/layout/activity_tv_receiver.xml",', '"tv/app/src/main/res/layout/activity_tv_receiver.xml",')
    if "TvMediaPlayer.kt" not in src:
        src = src.replace('need tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt \'pair_code\'\n',
            'need tv/app/src/main/java/com/pslink/tv/TvReceiverActivity.kt \'pair_code\'\n'
            'need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt \'MediaCodec.createDecoderByType\'\n'
            'need tv/app/src/main/java/com/pslink/tv/TvCaptionOverlayView.kt \'TextDirectionHeuristics.RTL\'\n'
            'need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt \'sendVideoSample\'\n')
    if '"tv/app/src/main/res/layout/activity_tv_receiver.xml",' not in src:
        src = src.replace('    "tv/app/src/main/AndroidManifest.xml",\n',
                          '    "tv/app/src/main/AndroidManifest.xml",\n    "tv/app/src/main/res/layout/activity_tv_receiver.xml",\n')
    anchor = 'echo "PS Link feature static checks passed. Gradle compilation and live PS5/TV hardware tests are still required."'
    required_checks = (
        "need tv/app/src/main/java/com/pslink/tv/TvSubtitleServer.kt 'Frame.Binary'",
        "need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'AudioTrack.Builder'",
        "need mobile/src/main/java/com/pslink/mobile/TvSubtitleClient.kt 'MEDIA_AUDIO_PCM16'",
        "need scripts/prepare_pslink_live_tv_streaming.py 'PSLINK_LIVE_TV_STREAMING_V1'",
    )
    missing_checks = [line for line in required_checks if line not in src]
    if missing_checks:
        src = replace_once(src, anchor, "".join(line + "\\n" for line in missing_checks) + anchor,
                           "extend source checks for binary TV media")
    extra_checks = (
        "need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'captionGeneration'\n"
        "need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'VIDEO_QUEUE_CAPACITY = 96'\n"
        "need tv/app/src/main/java/com/pslink/tv/TvMediaPlayer.kt 'AudioTrack.Builder'\n"
    )
    anchor = 'echo "PS Link feature static checks passed. Gradle compilation and live PS5/TV hardware tests are still required."'
    if "'captionGeneration'" not in src:
        src = replace_once(src, anchor, extra_checks + anchor, "validate bounded TV playout and stale-caption cancellation")
    write(path, src)


def install_autorun() -> None:
    src = read(UPSTREAM_SCRIPT)
    if MARKER in src:
        return
    footer = r'''

# PSLINK_LIVE_TV_STREAMING_V1
# This must run after the existing native stream/audio recorder overlays: the live
# callback bridge shares the decoded PCM callback installed by that single pipeline.
import pathlib as _pslink_live_tv_pathlib
import runpy as _pslink_live_tv_runpy
_pslink_live_tv_script = _pslink_live_tv_pathlib.Path(__file__).resolve().parent / "prepare_pslink_live_tv_streaming.py"
if not _pslink_live_tv_script.is_file():
    raise SystemExit(f"Missing PS Link live TV streaming overlay: {_pslink_live_tv_script}")
_pslink_live_tv_runpy.run_path(str(_pslink_live_tv_script), run_name="__main__")
'''
    write(UPSTREAM_SCRIPT, src.rstrip() + footer + "\n")


def main() -> None:
    if not CHI.is_dir():
        raise SystemExit(f"Pinned Chiaki source tree not found: {CHI}; run bash scripts/bootstrap.sh first")
    required = [
        CHI / "android/app/src/main/cpp/video-decoder.h",
        CHI / "android/app/src/main/cpp/video-decoder.c",
        CHI / "android/app/src/main/cpp/audio-decoder.c",
        CHI / "android/app/src/main/cpp/chiaki-jni.c",
        CHI / "android/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt",
        CHI / "android/app/src/main/java/com/metallic/chiaki/session/StreamSession.kt",
        CHI / "android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt",
    ]
    for path in required:
        if not path.is_file():
            raise SystemExit(f"Pinned Chiaki source file missing: {path}")

    # The bootstrap script must install the existing native stream/audio recorder
    # callbacks first. The live bridge deliberately reuses those vetted decoder hooks.
    video_header = read(required[0])
    video_source = read(required[1])
    audio_source = read(required[2])
    audio_jni = read(required[3])
    prerequisites = {
        "video decoder recording mutex/CSD state": "recording_mutex" in video_header and "recording_csd[2]" in video_header,
        "Annex-B parameter-set parser": "pslink_analyze_access_unit" in video_source,
        "codec configuration cache": "pslink_store_codec_config" in video_source,
        "decoded PCM callback implementation": "android_chiaki_audio_recording_frame" in audio_jni,
        "decoded PCM callback registration": "android_chiaki_audio_decoder_set_record_cb(&session->audio_decoder, android_chiaki_audio_recording_frame, session);" in audio_jni,
        "PCM decoder callback dispatch": "record_frame_cb((int16_t *)codec_buf" in audio_source,
    }
    missing = [name for name, present in prerequisites.items() if not present]
    if missing:
        raise SystemExit("Required existing Chiaki capture hooks are missing: " + "; ".join(missing) + ". Run scripts/bootstrap.sh before this overlay.")

    snapshot = snapshot_transaction()
    try:
        patch_video_decoder()
        patch_chiaki_session_bridge()
        patch_phone_translation()
        patch_tv_app()
        patch_stream_activity_wiring()
        patch_static_checks()
        install_autorun()
    except BaseException:
        rollback_transaction(snapshot)
        raise

    print("PS Link live TV streaming overlay installed.")
    print("Path: Chiaki encoded H.264/H.265 access units + decoded PCM -> paired LAN WebSocket -> TV MediaCodec + AudioTrack.")
    print("Arabic captions include normalized OCR bounds and explicit clear packets; English glyphs are masked by an opaque box.")
    print("Run the project build and test with a real PS5 + Android TV. Runtime latency, codec support and A/V sync are not certified by this source patch.")


if __name__ == "__main__":
    main()
