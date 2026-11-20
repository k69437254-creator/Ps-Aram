#!/usr/bin/env python3
from pathlib import Path
from datetime import datetime
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
C_FILE = ROOT / "third_party/chiaki-ng/android/app/src/main/cpp/video-decoder.c"
H_FILE = ROOT / "third_party/chiaki-ng/android/app/src/main/cpp/video-decoder.h"
MARKER = "PSLINK_DECODER_SHUTDOWN_ORDER_FIX_V1"

if not C_FILE.is_file() or not H_FILE.is_file():
    sys.exit("ERROR: Chiaki decoder source files are missing.")

source = C_FILE.read_text(encoding="utf-8")
header = H_FILE.read_text(encoding="utf-8")

for field in ("output_thread_started", "codec_started"):
    if field not in header:
        sys.exit(f"ERROR: required decoder field is missing: {field}")

pattern = re.compile(
    r"(?ms)^static\s+void\s+kill_decoder\s*"
    r"\(\s*AndroidChiakiVideoDecoder\s*\*decoder\s*\)\s*\n\{.*?^\}"
)
matches = list(pattern.finditer(source))

if len(matches) != 1:
    sys.exit(f"ERROR: expected one kill_decoder definition, found {len(matches)}.")

def validate(text):
    found = pattern.search(text)
    if not found:
        return False
    body = found.group(0)
    join_pos = body.find("chiaki_thread_join")
    stop_pos = body.find("AMediaCodec_stop")
    delete_pos = body.find("AMediaCodec_delete")
    return (
        MARKER in body
        and 0 <= join_pos < stop_pos < delete_pos
        and "decoder->shutdown_output = true" in body
        and "decoder->output_thread_started = false" in body
    )

if MARKER in source:
    if not validate(source):
        sys.exit("ERROR: shutdown-order marker exists but validation failed.")
    print("Decoder shutdown order is already correctly patched.")
    sys.exit(0)

output_call = re.search(
    r"AMediaCodec_dequeueOutputBuffer\s*\((?:.|\n)*?\);", source
)
if not output_call or not re.search(r",\s*100000\s*\)", output_call.group(0)):
    sys.exit("ERROR: output thread does not have the expected bounded dequeue timeout.")

replacement = r'''static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
    /* PSLINK_DECODER_SHUTDOWN_ORDER_FIX_V1 */
    bool join_output;

    chiaki_mutex_lock(&decoder->codec_mutex);

    if(decoder->shutdown_output)
    {
        chiaki_mutex_unlock(&decoder->codec_mutex);
        return;
    }

    if(!decoder->codec && !decoder->window && !decoder->output_thread_started)
    {
        chiaki_mutex_unlock(&decoder->codec_mutex);
        return;
    }

    /*
     * Stop new input first. The output thread uses a bounded dequeue timeout,
     * observes this flag, and exits before the codec is stopped or deleted.
     */
    decoder->shutdown_output = true;
    join_output = decoder->output_thread_started;

    chiaki_mutex_unlock(&decoder->codec_mutex);

    if(join_output)
        chiaki_thread_join(&decoder->output_thread, NULL);

    /*
     * No output thread can now be using the codec. Stop and release it only
     * after the join to avoid concurrent MediaCodec operations.
     */
    chiaki_mutex_lock(&decoder->codec_mutex);

    if(decoder->codec)
    {
        if(decoder->codec_started)
        {
            media_status_t status = AMediaCodec_stop(decoder->codec);
            if(status != AMEDIA_OK)
                CHIAKI_LOGE(decoder->log, "AMediaCodec_stop failed: %d", (int)status);

            decoder->codec_started = false;
        }

        AMediaCodec_delete(decoder->codec);
        decoder->codec = NULL;
    }

    if(decoder->window)
    {
        ANativeWindow_release(decoder->window);
        decoder->window = NULL;
    }

    decoder->codec_started = false;
    decoder->output_thread_started = false;
    decoder->shutdown_output = false;

    chiaki_mutex_unlock(&decoder->codec_mutex);
}'''

backup_dir = ROOT / ".pslink-decoder-update-backups" / (
    "shutdown-order-" + datetime.now().strftime("%Y%m%d-%H%M%S")
)
backup_dir.mkdir(parents=True, exist_ok=True)
(backup_dir / "video-decoder.c").write_text(source, encoding="utf-8")

updated = source[:matches[0].start()] + replacement + source[matches[0].end():]
C_FILE.write_text(updated.rstrip() + "\n", encoding="utf-8")

verified = C_FILE.read_text(encoding="utf-8")
if not validate(verified):
    sys.exit("ERROR: post-patch shutdown-order validation failed.")

print("Decoder shutdown order patched and validated.")
print("Output thread join now precedes codec stop and deletion.")
print("Native compilation and real PS5 testing are still required.")
