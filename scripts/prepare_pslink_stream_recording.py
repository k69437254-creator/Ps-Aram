#!/usr/bin/env python3
"""Install the PS Link encoded-stream recorder into the pinned Chiaki Android source.

The recorder taps complete Annex-B access units delivered to Chiaki's Android video
sample callback and muxes those compressed frames to an MP4 video track. It does not
capture the Android display and does not touch MediaProjection.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHI = ROOT / "third_party" / "chiaki-ng"
MARKER = "PSLINK_NATIVE_STREAM_RECORDER_V1"


def require_file(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required Chiaki source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")


def add_after_once(src: str, anchor: str, addition: str, label: str) -> str:
    payload = addition.strip("\r\n")
    if payload and payload in src:
        return src
    n = src.count(anchor)
    if n != 1:
        raise SystemExit(f"Cannot patch {label}: expected one anchor, found {n}: {anchor!r}")
    return src.replace(anchor, anchor + addition, 1)


def add_before_once(src: str, anchor: str, addition: str, label: str) -> str:
    payload = addition.strip("\r\n")
    if payload and payload in src:
        return src
    n = src.count(anchor)
    if n != 1:
        raise SystemExit(f"Cannot patch {label}: expected one anchor, found {n}: {anchor!r}")
    return src.replace(anchor, addition + anchor, 1)


def remove_kotlin_block(src: str, declaration: str) -> str:
    """Remove a Kotlin property/function block using brace matching."""
    pos = src.find(declaration)
    if pos < 0:
        return src
    line_start = src.rfind("\n", 0, pos) + 1
    brace = src.find("{", pos)
    if brace < 0:
        line_end = src.find("\n", pos)
        return src[:line_start] + (src[line_end + 1:] if line_end >= 0 else "")

    depth = 0
    i = brace
    in_string = False
    triple = False
    escaped = False
    in_line_comment = False
    in_block_comment = False
    while i < len(src):
        c = src[i]
        nxt = src[i + 1] if i + 1 < len(src) else ""
        if in_line_comment:
            if c == "\n":
                in_line_comment = False
        elif in_block_comment:
            if c == "*" and nxt == "/":
                in_block_comment = False
                i += 1
        elif in_string:
            if triple:
                if src.startswith('"""', i):
                    in_string = False
                    triple = False
                    i += 2
            elif escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
        else:
            if c == "/" and nxt == "/":
                in_line_comment = True
                i += 1
            elif c == "/" and nxt == "*":
                in_block_comment = True
                i += 1
            elif c == '"':
                if src.startswith('"""', i):
                    in_string = True
                    triple = True
                    i += 2
                else:
                    in_string = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    if end < len(src) and src[end] == "\r":
                        end += 1
                    if end < len(src) and src[end] == "\n":
                        end += 1
                    return src[:line_start] + src[end:]
        i += 1
    raise SystemExit(f"Unclosed Kotlin block while removing {declaration!r}")


C_HELPERS = r'''

/* PSLINK_NATIVE_STREAM_RECORDER_V1 */
static bool pslink_find_start_code(const uint8_t *data, size_t size, size_t from,
        size_t *position, size_t *prefix_size)
{
    for(size_t i = from; i + 3 <= size; ++i)
    {
        if(data[i] == 0 && data[i + 1] == 0)
        {
            if(data[i + 2] == 1)
            {
                *position = i;
                *prefix_size = 3;
                return true;
            }
            if(i + 4 <= size && data[i + 2] == 0 && data[i + 3] == 1)
            {
                *position = i;
                *prefix_size = 4;
                return true;
            }
        }
    }
    return false;
}

static bool pslink_next_nal(const uint8_t *data, size_t size, size_t *cursor,
        const uint8_t **nal, size_t *nal_size, unsigned *nal_type)
{
    size_t start = 0, prefix = 0;
    if(!pslink_find_start_code(data, size, *cursor, &start, &prefix))
        return false;
    size_t nal_start = start + prefix;
    size_t next_start = 0, next_prefix = 0;
    bool has_next = pslink_find_start_code(data, size, nal_start, &next_start, &next_prefix);
    size_t nal_end = has_next ? next_start : size;
    while(nal_end > nal_start && data[nal_end - 1] == 0)
        --nal_end;
    if(nal_end <= nal_start)
    {
        *cursor = has_next ? next_start : size;
        return false;
    }
    *nal = data + nal_start;
    *nal_size = nal_end - nal_start;
    *nal_type = *nal_size ? (unsigned)(data[nal_start] & 0x1f) : 0;
    *cursor = has_next ? next_start : size;
    return true;
}

static bool pslink_append_csd(uint8_t **out, size_t *out_size,
        const uint8_t *nal, size_t nal_size)
{
    if(!nal || nal_size == 0 || nal_size > SIZE_MAX - *out_size - 4)
        return false;
    uint8_t *grown = realloc(*out, *out_size + 4 + nal_size);
    if(!grown)
        return false;
    grown[*out_size + 0] = 0;
    grown[*out_size + 1] = 0;
    grown[*out_size + 2] = 0;
    grown[*out_size + 3] = 1;
    memcpy(grown + *out_size + 4, nal, nal_size);
    *out = grown;
    *out_size += 4 + nal_size;
    return true;
}

static bool pslink_store_codec_config(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size)
{
    uint8_t *csd0 = NULL, *csd1 = NULL;
    size_t csd0_size = 0, csd1_size = 0;
    size_t cursor = 0;
    const uint8_t *nal = NULL;
    size_t nal_size = 0;
    unsigned nal_type = 0;
    bool is_h265 = chiaki_codec_is_h265(decoder->target_codec);

    while(cursor < size && pslink_next_nal(data, size, &cursor, &nal, &nal_size, &nal_type))
    {
        if(!is_h265)
        {
            if(nal_type == 7 && !pslink_append_csd(&csd0, &csd0_size, nal, nal_size))
                goto fail;
            if(nal_type == 8 && !pslink_append_csd(&csd1, &csd1_size, nal, nal_size))
                goto fail;
        }
        else
        {
            /* Android HEVC track format requires VPS + SPS + PPS in csd-0. */
            unsigned hevc_type = (unsigned)((nal[0] >> 1) & 0x3f);
            if((hevc_type == 32 || hevc_type == 33 || hevc_type == 34)
                    && !pslink_append_csd(&csd0, &csd0_size, nal, nal_size))
                goto fail;
        }
    }

    if(!csd0 || csd0_size == 0 || (!is_h265 && (!csd1 || csd1_size == 0)))
        goto fail;

    pthread_mutex_lock(&decoder->recording_mutex);
    free(decoder->recording_csd[0]);
    free(decoder->recording_csd[1]);
    decoder->recording_csd[0] = csd0;
    decoder->recording_csd_size[0] = csd0_size;
    decoder->recording_csd[1] = csd1;
    decoder->recording_csd_size[1] = csd1_size;
    pthread_mutex_unlock(&decoder->recording_mutex);
    return true;

fail:
    free(csd0);
    free(csd1);
    return false;
}

static bool pslink_analyze_access_unit(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size, bool *has_parameter_sets)
{
    size_t cursor = 0;
    const uint8_t *nal = NULL;
    size_t nal_size = 0;
    unsigned ignored_type = 0;
    bool h265 = chiaki_codec_is_h265(decoder->target_codec);
    bool has_vcl = false;
    *has_parameter_sets = false;

    while(cursor < size && pslink_next_nal(data, size, &cursor, &nal, &nal_size, &ignored_type))
    {
        if(nal_size == 0)
            continue;
        if(h265)
        {
            unsigned type = (unsigned)((nal[0] >> 1) & 0x3f);
            if(type >= 32 && type <= 34)
                *has_parameter_sets = true; /* VPS / SPS / PPS */
            if(type <= 31)
                has_vcl = true;
        }
        else
        {
            unsigned type = (unsigned)(nal[0] & 0x1f);
            if(type == 7 || type == 8)
                *has_parameter_sets = true; /* SPS / PPS */
            if(type >= 1 && type <= 5)
                has_vcl = true;
        }
    }
    return has_vcl;
}

static bool pslink_first_nal_is_parameter_set(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size)
{
    size_t start = 0, prefix = 0;
    if(!pslink_find_start_code(data, size, 0, &start, &prefix) || start + prefix >= size)
        return false;
    unsigned type;
    if(chiaki_codec_is_h265(decoder->target_codec))
    {
        type = (unsigned)((data[start + prefix] >> 1) & 0x3f);
        return type >= 32 && type <= 34;
    }
    type = (unsigned)(data[start + prefix] & 0x1f);
    return type == 7 || type == 8;
}

static bool pslink_is_key_frame(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size)
{
    size_t cursor = 0;
    const uint8_t *nal = NULL;
    size_t nal_size = 0;
    unsigned ignored_type = 0;
    bool h265 = chiaki_codec_is_h265(decoder->target_codec);
    while(cursor < size && pslink_next_nal(data, size, &cursor, &nal, &nal_size, &ignored_type))
    {
        if(!nal_size)
            continue;
        if(!h265 && (nal[0] & 0x1f) == 5)
            return true; /* H.264 IDR */
        if(h265)
        {
            unsigned type = (unsigned)((nal[0] >> 1) & 0x3f);
            if(type == 19 || type == 20 || type == 21)
                return true; /* HEVC IDR / CRA */
        }
    }
    return false;
}

static uint64_t pslink_monotonic_us(void)
{
    struct timespec ts;
    if(clock_gettime(CLOCK_MONOTONIC, &ts) != 0)
        return 0;
    return (uint64_t)ts.tv_sec * 1000000ULL + (uint64_t)ts.tv_nsec / 1000ULL;
}

static bool pslink_start_muxer(AndroidChiakiVideoDecoder *decoder, int fd,
        AMediaMuxer **out_muxer, ssize_t *out_track)
{
    uint8_t *csd0 = NULL, *csd1 = NULL;
    size_t csd0_size = 0, csd1_size = 0;
    pthread_mutex_lock(&decoder->recording_mutex);
    if(decoder->recording_csd[0] && decoder->recording_csd_size[0])
    {
        csd0_size = decoder->recording_csd_size[0];
        csd0 = malloc(csd0_size);
        if(csd0)
            memcpy(csd0, decoder->recording_csd[0], csd0_size);
    }
    if(decoder->recording_csd[1] && decoder->recording_csd_size[1])
    {
        csd1_size = decoder->recording_csd_size[1];
        csd1 = malloc(csd1_size);
        if(csd1)
            memcpy(csd1, decoder->recording_csd[1], csd1_size);
    }
    pthread_mutex_unlock(&decoder->recording_mutex);

    bool h265 = chiaki_codec_is_h265(decoder->target_codec);
    if(!csd0 || csd0_size == 0 || (!h265 && (!csd1 || csd1_size == 0)))
    {
        free(csd0);
        free(csd1);
        CHIAKI_LOGE(decoder->log, "PS Link recorder has no complete codec parameter sets");
        return false;
    }

    AMediaFormat *format = AMediaFormat_new();
    if(!format)
    {
        free(csd0);
        free(csd1);
        return false;
    }
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, h265 ? "video/hevc" : "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, decoder->target_width);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, decoder->target_height);
    AMediaFormat_setBuffer(format, "csd-0", csd0, csd0_size);
    if(!h265)
        AMediaFormat_setBuffer(format, "csd-1", csd1, csd1_size);

    AMediaMuxer *muxer = AMediaMuxer_new(fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    ssize_t track = muxer ? AMediaMuxer_addTrack(muxer, format) : -1;
    media_status_t status = (muxer && track >= 0) ? AMediaMuxer_start(muxer) : AMEDIA_ERROR_UNKNOWN;
    AMediaFormat_delete(format);
    free(csd0);
    free(csd1);

    if(!muxer || track < 0 || status != AMEDIA_OK)
    {
        if(muxer)
        {
            if(track >= 0)
                AMediaMuxer_stop(muxer);
            AMediaMuxer_delete(muxer);
        }
        CHIAKI_LOGE(decoder->log, "PS Link recorder could not initialize MP4 muxer (status %d)", (int)status);
        return false;
    }

    *out_muxer = muxer;
    *out_track = track;
    CHIAKI_LOGI(decoder->log, "PS Link game-stream MP4 recorder started");
    return true;
}

static void *pslink_recording_thread_main(void *user)
{
    AndroidChiakiVideoDecoder *decoder = user;
    AMediaMuxer *muxer = NULL;
    ssize_t track = -1;
    uint64_t first_pts = 0;
    uint64_t last_pts = 0;
    bool have_first_pts = false;
    bool have_written_sample = false;

    for(;;)
    {
        PsLinkRecordFrame frame = { 0 };
        bool stopping = false;
        pthread_mutex_lock(&decoder->recording_mutex);
        while(decoder->recording_queue_count == 0 && !decoder->recording_stopping)
            pthread_cond_wait(&decoder->recording_cond, &decoder->recording_mutex);
        if(decoder->recording_queue_count > 0)
        {
            frame = decoder->recording_queue[decoder->recording_queue_head];
            decoder->recording_queue[decoder->recording_queue_head].data = NULL;
            decoder->recording_queue[decoder->recording_queue_head].size = 0;
            decoder->recording_queue_head = (decoder->recording_queue_head + 1) % PSLINK_RECORD_QUEUE_CAPACITY;
            --decoder->recording_queue_count;
        }
        stopping = decoder->recording_stopping && decoder->recording_queue_count == 0 && !frame.data;
        pthread_mutex_unlock(&decoder->recording_mutex);

        if(stopping)
            break;
        if(!frame.data)
            continue;

        if(!muxer)
        {
            if(!frame.key_frame)
            {
                free(frame.data);
                continue;
            }
            if(!pslink_start_muxer(decoder, decoder->recording_fd, &muxer, &track))
            {
                free(frame.data);
                pthread_mutex_lock(&decoder->recording_mutex);
                decoder->recording_failed = true;
                decoder->recording_stopping = true;
                pthread_cond_broadcast(&decoder->recording_cond);
                pthread_mutex_unlock(&decoder->recording_mutex);
                continue;
            }
            pthread_mutex_lock(&decoder->recording_mutex);
            decoder->recording_muxer_started = true;
            pthread_mutex_unlock(&decoder->recording_mutex);
        }

        size_t sample_size = frame.size;
        if(!frame.data || sample_size == 0 || sample_size > INT32_MAX)
        {
            free(frame.data);
            continue;
        }

        uint64_t now = frame.pts_us;
        if(!have_first_pts)
        {
            first_pts = now;
            have_first_pts = true;
        }
        uint64_t pts = now >= first_pts ? now - first_pts : 0;
        if(have_written_sample && pts <= last_pts)
            pts = last_pts + 1;
        last_pts = pts;
        have_written_sample = true;

        AMediaCodecBufferInfo info;
        info.offset = 0;
        info.size = (int32_t)sample_size;
        info.presentationTimeUs = (int64_t)pts;
        info.flags = frame.key_frame ? AMEDIACODEC_BUFFER_FLAG_KEY_FRAME : 0;
        media_status_t write_status = AMediaMuxer_writeSampleData(muxer, (size_t)track, frame.data, &info);
        free(frame.data);
        if(write_status != AMEDIA_OK)
        {
            CHIAKI_LOGE(decoder->log, "PS Link recorder failed writing MP4 sample (%d)", (int)write_status);
            pthread_mutex_lock(&decoder->recording_mutex);
            decoder->recording_failed = true;
            decoder->recording_stopping = true;
            pthread_cond_broadcast(&decoder->recording_cond);
            pthread_mutex_unlock(&decoder->recording_mutex);
        }
    }

    bool muxer_finalized = false;
    if(muxer)
    {
        media_status_t stop_status = AMediaMuxer_stop(muxer);
        muxer_finalized = stop_status == AMEDIA_OK;
        if(!muxer_finalized)
            CHIAKI_LOGE(decoder->log, "PS Link recorder failed to finalize MP4 (%d)", (int)stop_status);
        AMediaMuxer_delete(muxer);
    }
    if(decoder->recording_fd >= 0)
    {
        close(decoder->recording_fd);
        decoder->recording_fd = -1;
    }
    pthread_mutex_lock(&decoder->recording_mutex);
    decoder->recording_muxer_started = muxer_finalized;
    if(!muxer_finalized && muxer)
        decoder->recording_failed = true;
    decoder->recording_requested = false;
    pthread_cond_broadcast(&decoder->recording_cond);
    pthread_mutex_unlock(&decoder->recording_mutex);
    CHIAKI_LOGI(decoder->log, "PS Link game-stream recorder stopped");
    return NULL;
}

static void pslink_recording_submit(AndroidChiakiVideoDecoder *decoder,
        const uint8_t *data, size_t size)
{
    if(!decoder || !data || size == 0)
        return;
    pthread_mutex_lock(&decoder->recording_mutex);
    bool recording_active = decoder->recording_requested && !decoder->recording_stopping
            && !decoder->recording_failed;
    pthread_mutex_unlock(&decoder->recording_mutex);

    if(!recording_active)
    {
        if(pslink_first_nal_is_parameter_set(decoder, data, size))
            pslink_store_codec_config(decoder, data, size);
        return;
    }

    bool has_parameter_sets = false;
    bool has_vcl = pslink_analyze_access_unit(decoder, data, size, &has_parameter_sets);
    if(has_parameter_sets && pslink_store_codec_config(decoder, data, size))
        CHIAKI_LOGI(decoder->log, "PS Link recorder codec configuration received");
    if(!has_vcl)
        return;

    pthread_mutex_lock(&decoder->recording_mutex);
    if(!decoder->recording_requested || decoder->recording_stopping || decoder->recording_failed
            || decoder->recording_queue_count >= PSLINK_RECORD_QUEUE_CAPACITY)
    {
        pthread_mutex_unlock(&decoder->recording_mutex);
        return;
    }
    uint8_t *copy = malloc(size);
    if(!copy)
    {
        pthread_mutex_unlock(&decoder->recording_mutex);
        return;
    }
    memcpy(copy, data, size);
    size_t tail = decoder->recording_queue_tail;
    decoder->recording_queue[tail].data = copy;
    decoder->recording_queue[tail].size = size;
    decoder->recording_queue[tail].pts_us = pslink_monotonic_us();
    decoder->recording_queue[tail].key_frame = pslink_is_key_frame(decoder, data, size);
    decoder->recording_queue_tail = (tail + 1) % PSLINK_RECORD_QUEUE_CAPACITY;
    ++decoder->recording_queue_count;
    pthread_cond_signal(&decoder->recording_cond);
    pthread_mutex_unlock(&decoder->recording_mutex);
}

bool android_chiaki_video_decoder_start_recording(AndroidChiakiVideoDecoder *decoder, int fd)
{
    if(!decoder || fd < 0)
        return false;
    int owned_fd = dup(fd);
    if(owned_fd < 0)
        return false;

    pthread_mutex_lock(&decoder->recording_mutex);
    while(decoder->recording_joining)
        pthread_cond_wait(&decoder->recording_cond, &decoder->recording_mutex);
    if(decoder->recording_requested || decoder->recording_thread_started)
    {
        pthread_mutex_unlock(&decoder->recording_mutex);
        close(owned_fd);
        return false;
    }
    decoder->recording_fd = owned_fd;
    decoder->recording_stopping = false;
    decoder->recording_failed = false;
    decoder->recording_muxer_started = false;
    decoder->recording_last_saved = false;
    decoder->recording_queue_head = 0;
    decoder->recording_queue_tail = 0;
    decoder->recording_queue_count = 0;
    decoder->recording_requested = true;
    int create_status = pthread_create(&decoder->recording_thread, NULL,
            pslink_recording_thread_main, decoder);
    if(create_status != 0)
    {
        decoder->recording_requested = false;
        decoder->recording_fd = -1;
        pthread_mutex_unlock(&decoder->recording_mutex);
        close(owned_fd);
        return false;
    }
    decoder->recording_thread_started = true;
    pthread_mutex_unlock(&decoder->recording_mutex);
    return true;
}

bool android_chiaki_video_decoder_stop_recording(AndroidChiakiVideoDecoder *decoder)
{
    if(!decoder)
        return false;

    pthread_mutex_lock(&decoder->recording_mutex);
    while(decoder->recording_joining)
        pthread_cond_wait(&decoder->recording_cond, &decoder->recording_mutex);
    if(!decoder->recording_thread_started)
    {
        bool saved = decoder->recording_last_saved;
        pthread_mutex_unlock(&decoder->recording_mutex);
        return saved;
    }

    decoder->recording_joining = true;
    decoder->recording_stopping = true;
    pthread_cond_broadcast(&decoder->recording_cond);
    pthread_mutex_unlock(&decoder->recording_mutex);

    pthread_join(decoder->recording_thread, NULL);

    pthread_mutex_lock(&decoder->recording_mutex);
    bool saved = decoder->recording_muxer_started && !decoder->recording_failed;
    decoder->recording_thread_started = false;
    decoder->recording_joining = false;
    decoder->recording_last_saved = saved;
    decoder->recording_muxer_started = false;
    decoder->recording_failed = false;
    pthread_cond_broadcast(&decoder->recording_cond);
    pthread_mutex_unlock(&decoder->recording_mutex);
    return saved;
}
'''


def patch_native_header() -> None:
    path = CHI / "android/app/src/main/cpp/video-decoder.h"
    src = require_file(path)
    if MARKER in src:
        return
    src = add_after_once(src, '#include <chiaki/log.h>', '\n#include <stdint.h>\n#include <stddef.h>\n#include <stdbool.h>\n#include <pthread.h>', 'video-decoder.h includes')
    queue_type = '''\ntypedef struct pslink_record_frame_t
{
\tuint8_t *data;
\tsize_t size;
\tuint64_t pts_us;
\tbool key_frame;
} PsLinkRecordFrame;

#define PSLINK_RECORD_QUEUE_CAPACITY 32
'''
    src = add_after_once(src, 'typedef struct AMediaCodec AMediaCodec;', queue_type, 'video-decoder recording queue type')
    fields = '''\n\t/* PSLINK_NATIVE_STREAM_RECORDER_V1 */
\tint recording_fd;
\tpthread_mutex_t recording_mutex;
\tpthread_cond_t recording_cond;
\tpthread_t recording_thread;
\tbool recording_thread_started;
\tbool recording_joining;
\tbool recording_requested;
\tbool recording_stopping;
\tbool recording_failed;
\tbool recording_muxer_started;
\tbool recording_last_saved;
\tsize_t recording_queue_head;
\tsize_t recording_queue_tail;
\tsize_t recording_queue_count;
\tPsLinkRecordFrame recording_queue[PSLINK_RECORD_QUEUE_CAPACITY];
\tuint8_t *recording_csd[2];
\tsize_t recording_csd_size[2];'''
    src = add_after_once(src, '\tChiakiCodec target_codec;', fields, 'video-decoder recording state')
    prototypes = '''\nbool android_chiaki_video_decoder_start_recording(AndroidChiakiVideoDecoder *decoder, int fd);
bool android_chiaki_video_decoder_stop_recording(AndroidChiakiVideoDecoder *decoder);'''
    src = add_after_once(src, 'bool android_chiaki_video_decoder_video_sample(uint8_t *buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void *user);', prototypes, 'video-decoder recording API')
    write(path, src)


def patch_native_decoder() -> None:
    path = CHI / "android/app/src/main/cpp/video-decoder.c"
    src = require_file(path)
    if MARKER in src:
        return
    src = add_after_once(src, '#include <media/NdkMediaFormat.h>', '\n#include <media/NdkMediaMuxer.h>\n#include <unistd.h>\n#include <time.h>\n#include <limits.h>\n#include <sys/types.h>\n#include <stdlib.h>', 'video-decoder.c media includes')
    helper_marker = '/* PSLINK_NATIVE_STREAM_RECORDER_V1 */'
    helper = C_HELPERS.replace('/* PSLINK_NATIVE_STREAM_RECORDER_V1 */', helper_marker)
    src = add_after_once(src, '#define INPUT_BUFFER_TIMEOUT_MS 10', '\n' + helper, 'native recorder implementation')

    init_anchor = 'decoder->target_codec = codec;'
    init_add = '''
	ChiakiErrorCode mutex_err = chiaki_mutex_init(&decoder->codec_mutex, false);
	if(mutex_err != CHIAKI_ERR_SUCCESS)
		return mutex_err;
	decoder->window = NULL;
	decoder->recording_fd = -1;
	decoder->recording_thread_started = false;
	decoder->recording_joining = false;
	decoder->recording_requested = false;
	decoder->recording_stopping = false;
	decoder->recording_failed = false;
	decoder->recording_muxer_started = false;
	decoder->recording_last_saved = false;
	decoder->recording_queue_head = 0;
	decoder->recording_queue_tail = 0;
	decoder->recording_queue_count = 0;
	memset(decoder->recording_queue, 0, sizeof(decoder->recording_queue));
	decoder->recording_csd[0] = NULL;
	decoder->recording_csd[1] = NULL;
	decoder->recording_csd_size[0] = 0;
	decoder->recording_csd_size[1] = 0;
	if(pthread_mutex_init(&decoder->recording_mutex, NULL) != 0)
	{
		chiaki_mutex_fini(&decoder->codec_mutex);
		return CHIAKI_ERR_UNKNOWN;
	}
	if(pthread_cond_init(&decoder->recording_cond, NULL) != 0)
	{
		pthread_mutex_destroy(&decoder->recording_mutex);
		chiaki_mutex_fini(&decoder->codec_mutex);
		return CHIAKI_ERR_UNKNOWN;
	}'''
    src = add_after_once(src, init_anchor, init_add, 'decoder recording initialization')
    init_match = re.search(
        r"ChiakiErrorCode\s+android_chiaki_video_decoder_init\s*\(AndroidChiakiVideoDecoder\s*\*decoder,.*?\)\s*\{(?P<body>.*?)\n\}",
        src,
        re.S,
    )
    if not init_match:
        raise SystemExit('Cannot finalize decoder initialization: init function signature changed')
    init_body, replaced = re.subn(
        r"return\s+chiaki_mutex_init\(&decoder->codec_mutex,\s*false\);",
        'return CHIAKI_ERR_SUCCESS;',
        init_match.group('body'),
        count=1,
    )
    if replaced != 1:
        raise SystemExit('Cannot finalize decoder initialization: expected one codec mutex initialization return')
    src = src[:init_match.start('body')] + init_body + src[init_match.end('body'):]


    fini_match = re.search(
        r"void\s+android_chiaki_video_decoder_fini\s*\(AndroidChiakiVideoDecoder\s*\*decoder\)\s*\{(?P<body>.*?)\n\}",
        src,
        re.S,
    )
    if not fini_match:
        raise SystemExit('Cannot patch decoder recording cleanup: finalizer signature changed')
    fini_body = fini_match.group('body')
    cleanup_anchor = 'chiaki_mutex_fini(&decoder->codec_mutex);'
    if fini_body.count(cleanup_anchor) != 1:
        raise SystemExit('Cannot patch decoder recording cleanup: expected one codec mutex finalizer')
    cleanup = """
    free(decoder->recording_csd[0]);
    free(decoder->recording_csd[1]);
    pthread_cond_destroy(&decoder->recording_cond);
    pthread_mutex_destroy(&decoder->recording_mutex);
    """
    new_fini_body = fini_body.replace(cleanup_anchor, cleanup + '\n    ' + cleanup_anchor, 1)
    src = src[:fini_match.start('body')] + new_fini_body + src[fini_match.end('body'):]

    # Tap the original encoded access unit after the decoder-state guard and
    # before the loop consumes/splits the input buffer. Match indentation rather
    # than assuming a particular guard spelling or whitespace layout.
    sample_fn = re.search(
        r"bool\s+android_chiaki_video_decoder_video_sample\s*\([^)]*\)\s*\{(?P<body>.*?)\n\}",
        src,
        re.S,
    )
    if not sample_fn:
        raise SystemExit('Cannot patch video sample recording tap: callback signature changed')
    sample_body = sample_fn.group('body')
    loop_matches = list(re.finditer(r"(?m)^([ \t]*)while\s*\(\s*buf_size\s*>\s*0\s*\)", sample_body))
    if len(loop_matches) != 1:
        raise SystemExit(f'Cannot patch video sample recording tap: expected one input loop, found {len(loop_matches)}')
    loop = loop_matches[0]
    indent = loop.group(1)
    tap = indent + 'pslink_recording_submit(decoder, buf, buf_size);\n\n'
    sample_body = sample_body[:loop.start()] + tap + sample_body[loop.start():]
    src = src[:sample_fn.start('body')] + sample_body + src[sample_fn.end('body'):]

    kill_pattern = r'(static\s+void\s+kill_decoder\s*\(AndroidChiakiVideoDecoder\s*\*decoder\)\s*\{)'
    kill_match = re.search(kill_pattern, src)
    if not kill_match:
        raise SystemExit('Cannot patch kill_decoder: function declaration changed')
    kill_add = '\n    android_chiaki_video_decoder_stop_recording(decoder);'
    if kill_add.strip() not in src:
        src = src[:kill_match.end()] + kill_add + src[kill_match.end():]
    write(path, src)


def patch_jni() -> None:
    path = CHI / "android/app/src/main/cpp/chiaki-jni.c"
    src = require_file(path)
    marker = 'PSLINK_NATIVE_STREAM_RECORDER_JNI_V1'
    if marker in src:
        return
    anchor = 'JNIEXPORT jint JNICALL JNI_FCN(sessionStart)(JNIEnv *env, jobject obj, jlong ptr)'
    block = r'''/* PSLINK_NATIVE_STREAM_RECORDER_JNI_V1 */
JNIEXPORT jboolean JNICALL JNI_FCN(sessionStartVideoRecording)(JNIEnv *env, jobject obj, jlong ptr, jint fd)
{
    (void)env;
    (void)obj;
    AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
    if(!session || fd < 0)
        return JNI_FALSE;
    if(!android_chiaki_video_decoder_start_recording(&session->video_decoder, (int)fd))
        return JNI_FALSE;
    ChiakiErrorCode err = chiaki_session_request_idr(&session->session);
    if(err != CHIAKI_ERR_SUCCESS)
        CHIAKI_LOGW(session->log, "PS Link recording started; IDR request failed: %s", chiaki_error_string(err));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL JNI_FCN(sessionStopVideoRecording)(JNIEnv *env, jobject obj, jlong ptr)
{
    (void)env;
    (void)obj;
    AndroidChiakiSession *session = (AndroidChiakiSession *)ptr;
    if(!session)
        return JNI_FALSE;
    return android_chiaki_video_decoder_stop_recording(&session->video_decoder) ? JNI_TRUE : JNI_FALSE;
}

'''
    src = add_before_once(src, anchor, block, 'Chiaki JNI recording entry points')
    write(path, src)


def patch_chiaki_kt() -> None:
    path = CHI / "android/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt"
    src = require_file(path)
    if "sessionStartVideoRecording" not in src:
        anchor = '\t\t@JvmStatic external fun sessionSetSurface(ptr: Long, surface: Surface?)'
        add = '''
\t\t@JvmStatic external fun sessionStartVideoRecording(ptr: Long, fd: Int): Boolean
\t\t@JvmStatic external fun sessionStopVideoRecording(ptr: Long): Boolean'''
        src = add_after_once(src, anchor, add, 'Chiaki.kt external recording methods')
    if "fun startVideoRecording(fd: Int)" not in src:
        anchor = '''\tfun setSurface(surface: Surface?)
\t{
\t\tChiakiNative.sessionSetSurface(nativePtr, surface)
\t}'''
        add = '''

\tfun startVideoRecording(fd: Int): Boolean
\t\t= ChiakiNative.sessionStartVideoRecording(nativePtr, fd)

\tfun stopVideoRecording(): Boolean
\t\t= ChiakiNative.sessionStopVideoRecording(nativePtr)'''
        src = add_after_once(src, anchor, add, 'Chiaki.kt Session recording methods')
    write(path, src)


def patch_stream_activity() -> None:
    path = CHI / "android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
    src = require_file(path)

    # Remove only the old phone-screen capture launchers/receiver, not unrelated UI logic.
    for declaration in (
        "private val psLinkNotificationPermissionLauncher",
        "private val psLinkProjectionLauncher",
        "private val psLinkRecordingReceiver",
    ):
        src = remove_kotlin_block(src, declaration)

    src = re.sub(r"(?m)^\s*private var psLinkRecordingReceiverRegistered\s*=.*\n", "", src)
    src = re.sub(r"(?m)^\s*private var psLinkRecordingActive\s*=.*\n", "", src)

    # The former onStart/onStop overrides existed only to register the MediaProjection result receiver.
    for declaration in ("override fun onStart()", "override fun onStop()"):
        pos = src.find(declaration)
        if pos >= 0:
            brace = src.find("{", pos)
            end = src.find("}", brace)
            if brace >= 0 and end >= 0:
                body = src[brace:end + 1]
                if "psLinkRecordingReceiver" in body or "ScreenRecordingService" in body:
                    src = remove_kotlin_block(src, declaration)

    # Replace the previous MediaProjection click action with the native Chiaki stream recorder.
    start = src.find("binding.psLinkRecordButton.setOnClickListener")
    if start >= 0:
        end = src.find("binding.psLinkControlsButton.setOnClickListener", start)
        if end < 0:
            raise SystemExit("StreamActivity: could not locate the controls listener after record button")
        line_start = src.rfind("\n", 0, start) + 1
        replacement = '''\t\tbinding.psLinkRecordButton.setOnClickListener {
\t\t\tif(psLinkRecordingActive)
\t\t\t\tpsLinkStopGameRecording()
\t\t\telse
\t\t\t\tpsLinkStartGameRecording()
\t\t}
\n\t\t'''
        src = src[:line_start] + replacement + src[end:]
    else:
        raise SystemExit("StreamActivity: psLinkRecordButton listener was not found")

    # Remove stale imports from the phone-display recorder path.
    stale_imports = (
        "import android.media.projection.MediaProjectionManager",
        "import com.pslink.mobile.capture.ScreenRecordingService",
        "import com.pslink.mobile.capture.VideoEditorActivity",
        "import android.content.BroadcastReceiver",
        "import android.content.IntentFilter",
        "import androidx.activity.result.contract.ActivityResultContracts",
    )
    for line in stale_imports:
        src = src.replace(line + "\n", "")

    add_imports = '''import android.content.ContentValues
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
'''
    package_line = re.search(r"(?m)^package [^\n]+\n", src)
    if not package_line:
        raise SystemExit("StreamActivity: package declaration not found")
    missing = []
    for line in add_imports.splitlines():
        if line and line not in src:
            missing.append(line)
    if missing:
        insert = "\n" + "\n".join(missing) + "\n"
        src = src[:package_line.end()] + insert + src[package_line.end():]

    property_anchor = "\tprivate lateinit var binding: ActivityStreamBinding"
    props = '''
\tprivate var psLinkRecordingActive = false
\tprivate var psLinkRecordingDescriptor: ParcelFileDescriptor? = null
\tprivate var psLinkRecordingUri: Uri? = null
\tprivate var psLinkRecordingLegacyFile: File? = null'''
    if "private var psLinkRecordingDescriptor" not in src:
        src = add_after_once(src, property_anchor, props, 'StreamActivity recording state')

    helper_marker = "private fun psLinkStartGameRecording()"
    if helper_marker not in src:
        helper = r'''
	private fun psLinkStartGameRecording()
	{
		if(viewModel.session.session == null)
			return

		val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
		val fileName = "PSLink_Game_$stamp.mp4"
		var uri: Uri? = null
		var legacyFile: File? = null
		var descriptor: ParcelFileDescriptor? = null
		try
		{
			val openedDescriptor: ParcelFileDescriptor
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			{
				val values = ContentValues().apply {
					put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
					put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
					put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PS Link")
					put(MediaStore.Video.Media.IS_PENDING, 1)
				}
				val createdUri = contentResolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
					?: throw IllegalStateException("تعذر إنشاء ملف الفيديو")
				uri = createdUri
				openedDescriptor = contentResolver.openFileDescriptor(createdUri, "rw")
					?: throw IllegalStateException("تعذر فتح ملف الفيديو")
			}
			else
			{
				val directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
				if(!directory.exists() && !directory.mkdirs())
					throw IllegalStateException("تعذر إنشاء مجلد الفيديو")
				val createdFile = File(directory, fileName)
				legacyFile = createdFile
				openedDescriptor = ParcelFileDescriptor.open(createdFile,
					ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)
				uri = Uri.fromFile(createdFile)
			}
			descriptor = openedDescriptor

			if(viewModel.session.session?.startVideoRecording(openedDescriptor.fd) != true)
				throw IllegalStateException("تعذر تشغيل مسجل بث PS5")

			psLinkRecordingDescriptor = descriptor
			psLinkRecordingUri = uri
			psLinkRecordingLegacyFile = legacyFile
			psLinkRecordingActive = true
			binding.psLinkRecordButton.text = "إيقاف التسجيل"
			android.widget.Toast.makeText(this, "بدأ تسجيل بث لعبة PS5", android.widget.Toast.LENGTH_SHORT).show()
		}
		catch(error: Exception)
		{
			try { descriptor?.close() } catch(_: Exception) {}
			if(uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
				contentResolver.delete(uri, null, null)
			legacyFile?.delete()
			android.widget.Toast.makeText(this, "تعذر بدء التسجيل: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
		}
	}

	private fun psLinkStopGameRecording()
	{
		if(!psLinkRecordingActive && psLinkRecordingDescriptor == null)
			return

		val hasVideo = viewModel.session.session?.stopVideoRecording() == true
		try { psLinkRecordingDescriptor?.close() } catch(_: Exception) {}
		psLinkRecordingDescriptor = null
		val uri = psLinkRecordingUri
		val legacyFile = psLinkRecordingLegacyFile
		psLinkRecordingUri = null
		psLinkRecordingLegacyFile = null
		psLinkRecordingActive = false
		binding.psLinkRecordButton.text = "تسجيل اللعبة"

		if(hasVideo && uri != null)
		{
			if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
			{
				val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
				contentResolver.update(uri, values, null, null)
			}
			else if(legacyFile != null)
			{
				MediaScannerConnection.scanFile(this, arrayOf(legacyFile.absolutePath), arrayOf("video/mp4"), null)
			}
			android.widget.Toast.makeText(this, "حُفظ فيديو لعبة PS5 في مجلد Movies/PS Link", android.widget.Toast.LENGTH_LONG).show()
		}
		else
		{
			if(uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
				contentResolver.delete(uri, null, null)
			legacyFile?.delete()
			android.widget.Toast.makeText(this, "لم يصل إطار فيديو قابل للحفظ؛ لم يُنشأ تسجيل صالح", android.widget.Toast.LENGTH_LONG).show()
		}
	}
'''
        src = add_before_once(src, "\toverride fun onResume()", helper + "\n\n", 'StreamActivity record handlers')

    # Close and finalize an active MP4 before the session is paused/shut down.
    pause_marker = "\toverride fun onPause()\n\t{"
    if pause_marker in src and "psLinkStopGameRecording()\n\t\tsuper.onPause()" not in src:
        src = src.replace(pause_marker, pause_marker + "\n\t\tpsLinkStopGameRecording()", 1)

    write(path, src)


def main() -> None:
    if not CHI.is_dir():
        raise SystemExit(f"Chiaki source tree not found: {CHI}; run scripts/bootstrap.sh first")
    patch_native_header()
    patch_native_decoder()
    patch_jni()
    patch_chiaki_kt()
    patch_stream_activity()
    audio_overlay = ROOT / "scripts" / "prepare_pslink_audio_recording.py"
    if not audio_overlay.is_file():
        raise SystemExit(f"Missing PS Link audio recorder overlay: {audio_overlay}")
    import runpy as _pslink_audio_runpy
    _pslink_audio_runpy.run_path(str(audio_overlay), run_name="__main__")
    print("PS Link native game-stream recorder overlay applied.")
    print("Capture source: Chiaki encoded PS5 access units; output: video-only MP4.")
    print("Phone display capture/MediaProjection is not used by this recorder.")
    print("A native Android build and a real PS5 recording test are still required.")


if __name__ == "__main__":
    main()
