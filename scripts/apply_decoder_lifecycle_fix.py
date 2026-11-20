#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "third_party/chiaki-ng/android/app/src/main/cpp"
HEADER = CPP / "video-decoder.h"
SOURCE = CPP / "video-decoder.c"
MARKER = "PSLINK_DECODER_LIFECYCLE_FIX_V1"

def replace_function(src, signature, replacement):
    positions = []
    pos = 0

    while True:
        pos = src.find(signature, pos)
        if pos < 0:
            break

        brace = src.find("{", pos)
        semi = src.find(";", pos)

        if brace >= 0 and (semi < 0 or brace < semi):
            positions.append((pos, brace))

        pos += len(signature)

    if len(positions) != 1:
        raise RuntimeError(
            "Expected exactly one function definition for: " + signature
        )

    start, brace = positions[0]
    depth = 0
    end = -1

    for i in range(brace, len(src)):
        if src[i] == "{":
            depth += 1
        elif src[i] == "}":
            depth -= 1
            if depth == 0:
                end = i + 1
                break

    if end < 0:
        raise RuntimeError("Unclosed function body: " + signature)

    return src[:start] + replacement + src[end:]

if not HEADER.is_file() or not SOURCE.is_file():
    sys.exit("ERROR: Chiaki source missing. Run scripts/bootstrap.sh first.")

h = HEADER.read_text(encoding="utf-8")
c = SOURCE.read_text(encoding="utf-8")

if MARKER in c:
    print("Decoder lifecycle fix already exists.")
    sys.exit(0)

import re

pattern = r"(?m)^[ \t]*ChiakiThread output_thread;[ \t]*$"
matches = list(re.finditer(pattern, h))

if len(matches) != 1:
    sys.exit("ERROR: expected exactly one output_thread field")

if "output_thread_started" in h or "codec_started" in h:
    sys.exit("ERROR: decoder state fields already exist")

m = matches[0]
replacement = m.group(0) + "\n\tbool output_thread_started;\n\tbool codec_started;"
h = h[:m.start()] + replacement + h[m.end():]

c = replace_function(
    c,
    "ChiakiErrorCode android_chiaki_video_decoder_init(",
    """ChiakiErrorCode android_chiaki_video_decoder_init(AndroidChiakiVideoDecoder *decoder, ChiakiLog *log, int32_t target_width, int32_t target_height, ChiakiCodec codec)
{
    decoder->log = log;
    decoder->codec = NULL;
    decoder->window = NULL;
    decoder->timestamp_cur = 0;
    decoder->target_width = target_width;
    decoder->target_height = target_height;
    decoder->target_codec = codec;
    decoder->shutdown_output = false;
    decoder->output_thread_started = false;
    decoder->codec_started = false;
    return chiaki_mutex_init(&decoder->codec_mutex, false);
}"""
)

c = replace_function(
    c,
    "static void kill_decoder(",
    """static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
    bool join_output;

    chiaki_mutex_lock(&decoder->codec_mutex);

    if(!decoder->codec && !decoder->window)
    {
        chiaki_mutex_unlock(&decoder->codec_mutex);
        return;
    }

    decoder->shutdown_output = true;

    if(decoder->codec && decoder->codec_started)
    {
        ssize_t index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);

        if(index >= 0)
        {
            AMediaCodec_queueInputBuffer(
                decoder->codec,
                (size_t)index,
                0,
                0,
                decoder->timestamp_cur++,
                AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM
            );
        }

        media_status_t status = AMediaCodec_stop(decoder->codec);
        if(status != AMEDIA_OK)
            CHIAKI_LOGE(decoder->log, "AMediaCodec_stop failed: %d", (int)status);

        decoder->codec_started = false;
    }

    join_output = decoder->output_thread_started;
    chiaki_mutex_unlock(&decoder->codec_mutex);

    /*
     * Join before deleting the codec. The output thread uses a bounded
     * dequeue timeout and checks shutdown_output before releasing buffers.
     */
    if(join_output)
        chiaki_thread_join(&decoder->output_thread, NULL);

    chiaki_mutex_lock(&decoder->codec_mutex);

    if(decoder->codec)
    {
        AMediaCodec_delete(decoder->codec);
        decoder->codec = NULL;
    }

    if(decoder->window)
    {
        ANativeWindow_release(decoder->window);
        decoder->window = NULL;
    }

    decoder->output_thread_started = false;
    decoder->shutdown_output = false;

    chiaki_mutex_unlock(&decoder->codec_mutex);
}"""
)

c = replace_function(
    c,
    "void android_chiaki_video_decoder_fini(",
    """void android_chiaki_video_decoder_fini(AndroidChiakiVideoDecoder *decoder)
{
    kill_decoder(decoder);
    chiaki_mutex_fini(&decoder->codec_mutex);
}"""
)

c = replace_function(
    c,
    "void android_chiaki_video_decoder_set_surface(",
    """void android_chiaki_video_decoder_set_surface(AndroidChiakiVideoDecoder *decoder, JNIEnv *env, jobject surface)
{
    chiaki_mutex_lock(&decoder->codec_mutex);

    if(decoder->shutdown_output)
    {
        chiaki_mutex_unlock(&decoder->codec_mutex);
        return;
    }

    if(!surface)
    {
        chiaki_mutex_unlock(&decoder->codec_mutex);
        kill_decoder(decoder);
        CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
        return;
    }

    if(decoder->codec)
    {
#if __ANDROID_API__ >= 23
        ANativeWindow *new_window = ANativeWindow_fromSurface(env, surface);

        if(!new_window)
        {
            CHIAKI_LOGE(decoder->log, "Failed to create replacement native window");
            goto beach;
        }

        media_status_t status = AMediaCodec_setOutputSurface(decoder->codec, new_window);

        if(status != AMEDIA_OK)
        {
            CHIAKI_LOGE(decoder->log, "AMediaCodec_setOutputSurface failed: %d", (int)status);
            ANativeWindow_release(new_window);
            goto beach;
        }

        if(decoder->window)
            ANativeWindow_release(decoder->window);

        decoder->window = new_window;
#else
        CHIAKI_LOGE(decoder->log, "Video decoder already initialized");
#endif
        goto beach;
    }

    decoder->window = ANativeWindow_fromSurface(env, surface);

    if(!decoder->window)
    {
        CHIAKI_LOGE(decoder->log, "ANativeWindow_fromSurface failed");
        goto beach;
    }

    const char *mime = chiaki_codec_is_h265(decoder->target_codec)
        ? "video/hevc" : "video/avc";

    decoder->codec = AMediaCodec_createDecoderByType(mime);

    if(!decoder->codec)
    {
        CHIAKI_LOGE(decoder->log, "Failed to create decoder: %s", mime);
        goto error_surface;
    }

    AMediaFormat *format = AMediaFormat_new();

    if(!format)
    {
        CHIAKI_LOGE(decoder->log, "AMediaFormat_new failed");
        goto error_codec;
    }

    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, mime);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);

    media_status_t status = AMediaCodec_configure(
        decoder->codec, format, decoder->window, NULL, 0
    );

    if(status != AMEDIA_OK)
    {
        CHIAKI_LOGE(decoder->log, "AMediaCodec_configure failed: %d", (int)status);
        AMediaFormat_delete(format);
        goto error_codec;
    }

    status = AMediaCodec_start(decoder->codec);
    AMediaFormat_delete(format);

    if(status != AMEDIA_OK)
    {
        CHIAKI_LOGE(decoder->log, "AMediaCodec_start failed: %d", (int)status);
        goto error_codec;
    }

    decoder->codec_started = true;

    ChiakiErrorCode err = chiaki_thread_create(
        &decoder->output_thread,
        android_chiaki_video_decoder_output_thread_func,
        decoder
    );

    if(err != CHIAKI_ERR_SUCCESS)
    {
        CHIAKI_LOGE(decoder->log, "Failed to create decoder output thread");
        goto error_codec;
    }

    decoder->output_thread_started = true;
    goto beach;

error_codec:
    if(decoder->codec_started)
    {
        AMediaCodec_stop(decoder->codec);
        decoder->codec_started = false;
    }

    if(decoder->codec)
    {
        AMediaCodec_delete(decoder->codec);
        decoder->codec = NULL;
    }

error_surface:
    if(decoder->window)
    {
        ANativeWindow_release(decoder->window);
        decoder->window = NULL;
    }

beach:
    chiaki_mutex_unlock(&decoder->codec_mutex);
}"""
)

sample_sig = "bool android_chiaki_video_decoder_video_sample("
sample_start = c.find(sample_sig)
if sample_start < 0:
    sys.exit("ERROR: video_sample function not found.")

sample_end = c.find("static void *android_chiaki_video_decoder_output_thread_func(", sample_start)
if sample_end < 0:
    sys.exit("ERROR: output thread function boundary not found.")

sample = c[sample_start:sample_end]
old_guard = "if(!decoder->codec)"
if sample.count(old_guard) != 1:
    sys.exit("ERROR: video_sample codec guard differs from expected source.")

sample = sample.replace(
    old_guard,
    "if(!decoder->codec || decoder->shutdown_output)",
    1
)
c = c[:sample_start] + sample + c[sample_end:]

c = replace_function(
    c,
    "static void *android_chiaki_video_decoder_output_thread_func(",
    """static void *android_chiaki_video_decoder_output_thread_func(void *user)
{
    AndroidChiakiVideoDecoder *decoder = user;

    while(1)
    {
        AMediaCodecBufferInfo info;

        ssize_t status = AMediaCodec_dequeueOutputBuffer(
            decoder->codec, &info, 100000
        );

        if(status >= 0)
        {
            chiaki_mutex_lock(&decoder->codec_mutex);

            bool shutdown = decoder->shutdown_output;

            if(!shutdown)
            {
                AMediaCodec_releaseOutputBuffer(
                    decoder->codec, (size_t)status, info.size != 0
                );
            }

            chiaki_mutex_unlock(&decoder->codec_mutex);

            if(shutdown)
                break;

            if(info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM)
            {
                CHIAKI_LOGI(decoder->log, "AMediaCodec reported EOS");
                break;
            }
        }
        else
        {
            chiaki_mutex_lock(&decoder->codec_mutex);
            bool shutdown = decoder->shutdown_output;
            chiaki_mutex_unlock(&decoder->codec_mutex);

            if(shutdown)
                break;
        }
    }

    CHIAKI_LOGI(decoder->log, "Video Decoder Output Thread exiting");
    return NULL;
}"""
)

c = c.replace(
    '#include "video-decoder.h"\n',
    '#include "video-decoder.h"\n\n/* ' + MARKER + ' */\n',
    1
)

checks = [
    "decoder->output_thread_started = true;",
    "decoder->codec_started = true;",
    "AMediaCodec_dequeueOutputBuffer(\n            decoder->codec, &info, 100000",
    "if(!decoder->codec || decoder->shutdown_output)",
    "chiaki_thread_join(&decoder->output_thread, NULL);",
    "ANativeWindow_release(decoder->window);",
]

for item in checks:
    if item not in c:
        sys.exit("ERROR: post-patch validation failed: " + item)

# Back up both files before writing.
backup = ROOT / ".pslink-decoder-update-backups"
backup.mkdir(exist_ok=True)

import time
stamp = time.strftime("%Y%m%d-%H%M%S")
(backup / ("video-decoder.h." + stamp + ".bak")).write_text(
    HEADER.read_text(encoding="utf-8"), encoding="utf-8"
)
(backup / ("video-decoder.c." + stamp + ".bak")).write_text(
    SOURCE.read_text(encoding="utf-8"), encoding="utf-8"
)

HEADER.write_text(h, encoding="utf-8")
SOURCE.write_text(c, encoding="utf-8")

print("Decoder lifecycle source patch applied.")
print("Validated: mutex handoff, bounded dequeue, thread join, codec/window cleanup.")
print("Native compilation and real PS5 testing are still required.")
