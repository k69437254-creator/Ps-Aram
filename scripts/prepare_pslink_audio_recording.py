#!/usr/bin/env python3
"""Extend PS Link's native Chiaki stream recorder with synchronized AAC game audio.

This overlay is applied after prepare_pslink_stream_recording.py has installed the
video-only recorder. It records decoded Chiaki PCM, encodes it to AAC with Android's
NDK MediaCodec, and muxes it beside length-prefixed H.264/H.265 video samples.
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CHI = ROOT / "third_party" / "chiaki-ng"
MARKER = "PSLINK_NATIVE_AUDIO_RECORDER_V1"


def read(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"Missing required Chiaki source: {path}")
    return path.read_text(encoding="utf-8")


def write(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", newline="\n")


def replace_c_function(source: str, pattern: str, replacement: str, label: str) -> str:
    matches = list(re.finditer(pattern, source))
    if len(matches) != 1:
        raise SystemExit(f"Cannot patch {label}: expected one function, found {len(matches)}")
    start = matches[0].start()
    brace = source.find("{", matches[0].end())
    if brace < 0:
        raise SystemExit(f"Cannot patch {label}: function body was not found")

    depth = 0
    i = brace
    in_string = False
    in_char = False
    escaped = False
    line_comment = False
    block_comment = False
    while i < len(source):
        c = source[i]
        nxt = source[i + 1] if i + 1 < len(source) else ""
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
                    end = i + 1
                    return source[:start] + replacement + source[end:]
        i += 1
    raise SystemExit(f"Cannot patch {label}: unmatched function braces")


MUX_HELPERS = r'''typedef struct pslink_mux_state_t
{
    AMediaMuxer *muxer;
    ssize_t video_track;
    ssize_t audio_track;
    bool started;
    uint8_t *pending_video;
    size_t pending_video_size;
    uint64_t pending_video_pts;
    uint64_t recording_start_us;
    uint64_t timeline_origin_us;
    bool have_timeline_origin;
    uint64_t last_video_pts;
    uint64_t last_audio_pts;
    bool have_video_pts;
    bool have_audio_pts;
} PsLinkMuxState;

static bool pslink_annexb_to_length_prefixed(const uint8_t *data, size_t size,
        uint8_t **out, size_t *out_size)
{
    uint8_t *converted = NULL;
    size_t converted_size = 0;
    size_t cursor = 0;
    const uint8_t *nal = NULL;
    size_t nal_size = 0;
    unsigned ignored_type = 0;

    while(cursor < size && pslink_next_nal(data, size, &cursor, &nal, &nal_size, &ignored_type))
    {
        if(nal_size == 0 || nal_size > UINT32_MAX || nal_size > SIZE_MAX - converted_size - 4)
        {
            free(converted);
            return false;
        }
        uint8_t *grown = realloc(converted, converted_size + 4 + nal_size);
        if(!grown)
        {
            free(converted);
            return false;
        }
        converted = grown;
        uint32_t length = (uint32_t)nal_size;
        converted[converted_size + 0] = (uint8_t)(length >> 24);
        converted[converted_size + 1] = (uint8_t)(length >> 16);
        converted[converted_size + 2] = (uint8_t)(length >> 8);
        converted[converted_size + 3] = (uint8_t)length;
        memcpy(converted + converted_size + 4, nal, nal_size);
        converted_size += 4 + nal_size;
    }

    if(converted_size == 0)
    {
        free(converted);
        return false;
    }
    *out = converted;
    *out_size = converted_size;
    return true;
}

static bool pslink_add_video_track(AndroidChiakiVideoDecoder *decoder, PsLinkMuxState *state)
{
    uint8_t *csd0 = NULL, *csd1 = NULL;
    size_t csd0_size = 0, csd1_size = 0;
    pthread_mutex_lock(&decoder->recording_mutex);
    if(decoder->recording_csd[0] && decoder->recording_csd_size[0])
    {
        csd0_size = decoder->recording_csd_size[0];
        csd0 = malloc(csd0_size);
        if(csd0) memcpy(csd0, decoder->recording_csd[0], csd0_size);
    }
    if(decoder->recording_csd[1] && decoder->recording_csd_size[1])
    {
        csd1_size = decoder->recording_csd_size[1];
        csd1 = malloc(csd1_size);
        if(csd1) memcpy(csd1, decoder->recording_csd[1], csd1_size);
    }
    pthread_mutex_unlock(&decoder->recording_mutex);

    bool h265 = chiaki_codec_is_h265(decoder->target_codec);
    if(!csd0 || csd0_size == 0 || (!h265 && (!csd1 || csd1_size == 0)))
    {
        free(csd0);
        free(csd1);
        CHIAKI_LOGW(decoder->log, "PS Link recorder is waiting for complete video codec configuration");
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
    if(!h265) AMediaFormat_setBuffer(format, "csd-1", csd1, csd1_size);

    state->video_track = AMediaMuxer_addTrack(state->muxer, format);
    AMediaFormat_delete(format);
    free(csd0);
    free(csd1);
    if(state->video_track < 0)
    {
        CHIAKI_LOGE(decoder->log, "PS Link recorder could not add its video track");
        return false;
    }
    CHIAKI_LOGI(decoder->log, "PS Link recorder video track configured");
    return true;
}

static bool pslink_write_video_sample(AndroidChiakiVideoDecoder *decoder, PsLinkMuxState *state,
        const uint8_t *data, size_t size, uint64_t absolute_pts, bool key_frame)
{
    uint8_t *sample = NULL;
    size_t sample_size = 0;
    if(!pslink_annexb_to_length_prefixed(data, size, &sample, &sample_size))
    {
        CHIAKI_LOGE(decoder->log, "PS Link recorder could not convert Annex-B video access unit");
        return false;
    }
    uint64_t pts = state->have_timeline_origin && absolute_pts >= state->timeline_origin_us
            ? absolute_pts - state->timeline_origin_us : 0;
    if(state->have_video_pts && pts <= state->last_video_pts) pts = state->last_video_pts + 1;
    state->last_video_pts = pts;
    state->have_video_pts = true;

    AMediaCodecBufferInfo info = { 0 };
    info.offset = 0;
    info.size = (int32_t)sample_size;
    info.presentationTimeUs = (int64_t)pts;
    info.flags = key_frame ? AMEDIACODEC_BUFFER_FLAG_KEY_FRAME : 0;
    media_status_t status = AMediaMuxer_writeSampleData(state->muxer, (size_t)state->video_track, sample, &info);
    free(sample);
    if(status != AMEDIA_OK)
    {
        CHIAKI_LOGE(decoder->log, "PS Link recorder failed writing video sample (%d)", (int)status);
        return false;
    }
    return true;
}

static bool pslink_try_start_muxer(AndroidChiakiVideoDecoder *decoder, PsLinkMuxState *state)
{
    if(state->started || state->video_track < 0 || state->audio_track < 0)
        return true;
    media_status_t status = AMediaMuxer_start(state->muxer);
    if(status != AMEDIA_OK)
    {
        CHIAKI_LOGE(decoder->log, "PS Link recorder could not start audio/video MP4 muxer (%d)", (int)status);
        return false;
    }
    state->started = true;
    pthread_mutex_lock(&decoder->recording_mutex);
    decoder->recording_muxer_started = true;
    pthread_mutex_unlock(&decoder->recording_mutex);
    CHIAKI_LOGI(decoder->log, "PS Link audio/video MP4 recorder started");

    if(state->pending_video)
    {
        bool written = pslink_write_video_sample(decoder, state, state->pending_video,
                state->pending_video_size, state->pending_video_pts, true);
        free(state->pending_video);
        state->pending_video = NULL;
        state->pending_video_size = 0;
        if(!written) return false;
    }
    return true;
}

static bool pslink_drain_audio_encoder(AndroidChiakiVideoDecoder *decoder, AMediaCodec *encoder,
        PsLinkMuxState *state, bool wait_for_output, bool *eos_seen)
{
    for(unsigned iteration = 0; iteration < 128; ++iteration)
    {
        AMediaCodecBufferInfo info = { 0 };
        ssize_t index = AMediaCodec_dequeueOutputBuffer(encoder, &info, wait_for_output ? 10000 : 0);
        if(index == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED)
        {
            if(state->audio_track < 0)
            {
                AMediaFormat *format = AMediaCodec_getOutputFormat(encoder);
                if(!format) return false;
                state->audio_track = AMediaMuxer_addTrack(state->muxer, format);
                AMediaFormat_delete(format);
                if(state->audio_track < 0)
                {
                    CHIAKI_LOGE(decoder->log, "PS Link recorder could not add its AAC audio track");
                    return false;
                }
                CHIAKI_LOGI(decoder->log, "PS Link recorder AAC audio track configured");
                if(!pslink_try_start_muxer(decoder, state)) return false;
            }
            continue;
        }
        if(index == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
            continue;
        if(index == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
        if(index < 0)
            break;

        size_t capacity = 0;
        uint8_t *buffer = AMediaCodec_getOutputBuffer(encoder, (size_t)index, &capacity);
        bool is_eos = (info.flags & AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0;
        if(buffer && info.size > 0 && !(info.flags & AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) && state->started)
        {
            uint64_t raw_pts = info.presentationTimeUs < 0 ? 0 : (uint64_t)info.presentationTimeUs;
            uint64_t origin_offset = state->have_timeline_origin && state->timeline_origin_us >= state->recording_start_us
                    ? state->timeline_origin_us - state->recording_start_us : 0;
            if(raw_pts >= origin_offset)
            {
                uint64_t pts = raw_pts - origin_offset;
                if(state->have_audio_pts && pts <= state->last_audio_pts) pts = state->last_audio_pts + 1;
                state->last_audio_pts = pts;
                state->have_audio_pts = true;
                info.presentationTimeUs = (int64_t)pts;
                media_status_t status = AMediaMuxer_writeSampleData(state->muxer,
                        (size_t)state->audio_track, buffer, &info);
                if(status != AMEDIA_OK)
                {
                    CHIAKI_LOGE(decoder->log, "PS Link recorder failed writing AAC sample (%d)", (int)status);
                    AMediaCodec_releaseOutputBuffer(encoder, (size_t)index, false);
                    return false;
                }
            }
        }
        AMediaCodec_releaseOutputBuffer(encoder, (size_t)index, false);
        if(is_eos)
        {
            *eos_seen = true;
            break;
        }
    }
    return true;
}

static bool pslink_feed_pcm(AndroidChiakiVideoDecoder *decoder, AMediaCodec *encoder,
        PsLinkMuxState *state, const PsLinkRecordFrame *frame, uint64_t recording_start_us)
{
    const size_t bytes_per_sample_frame = (size_t)decoder->recording_audio_channels * sizeof(int16_t);
    if(bytes_per_sample_frame == 0 || frame->size == 0 || frame->size % bytes_per_sample_frame != 0)
        return false;

    size_t offset = 0;
    unsigned retries = 0;
    while(offset < frame->size)
    {
        ssize_t index = AMediaCodec_dequeueInputBuffer(encoder, 10000);
        if(index < 0)
        {
            bool ignored_eos = false;
            if(!pslink_drain_audio_encoder(decoder, encoder, state, false, &ignored_eos)) return false;
            if(++retries > 30)
            {
                CHIAKI_LOGW(decoder->log, "PS Link recorder dropped a PCM block because AAC input stayed full");
                return true;
            }
            continue;
        }
        retries = 0;
        size_t capacity = 0;
        uint8_t *input = AMediaCodec_getInputBuffer(encoder, (size_t)index, &capacity);
        if(!input || capacity < bytes_per_sample_frame)
        {
            AMediaCodec_queueInputBuffer(encoder, (size_t)index, 0, 0, 0, 0);
            return false;
        }
        size_t chunk = frame->size - offset;
        if(chunk > capacity) chunk = capacity - (capacity % bytes_per_sample_frame);
        if(chunk == 0)
        {
            AMediaCodec_queueInputBuffer(encoder, (size_t)index, 0, 0, 0, 0);
            return false;
        }
        memcpy(input, frame->data + offset, chunk);
        uint64_t base_pts = frame->pts_us >= recording_start_us ? frame->pts_us - recording_start_us : 0;
        uint64_t sample_frames_before = offset / bytes_per_sample_frame;
        uint64_t pts = base_pts + sample_frames_before * 1000000ULL / decoder->recording_audio_sample_rate;
        media_status_t status = AMediaCodec_queueInputBuffer(encoder, (size_t)index, 0, chunk, (int64_t)pts, 0);
        if(status != AMEDIA_OK)
        {
            CHIAKI_LOGE(decoder->log, "PS Link recorder failed queuing PCM for AAC (%d)", (int)status);
            return false;
        }
        offset += chunk;
    }
    return true;
}
'''

WORKER = r'''static void *pslink_recording_thread_main(void *user)
{
    AndroidChiakiVideoDecoder *decoder = user;
    AMediaMuxer *muxer = AMediaMuxer_new(decoder->recording_fd, AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    AMediaCodec *audio_encoder = NULL;
    PsLinkMuxState state = { 0 };
    state.muxer = muxer;
    state.video_track = -1;
    state.audio_track = -1;
    uint64_t recording_start_us = decoder->recording_start_us;
    state.recording_start_us = recording_start_us;
    bool encoder_started = false;
    bool eos_seen = false;
    bool failed = muxer == NULL;

    if(!failed)
    {
        audio_encoder = AMediaCodec_createEncoderByType("audio/mp4a-latm");
        if(!audio_encoder)
            failed = true;
    }
    if(!failed)
    {
        AMediaFormat *format = AMediaFormat_new();
        if(!format)
            failed = true;
        else
        {
            AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "audio/mp4a-latm");
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_SAMPLE_RATE, (int32_t)decoder->recording_audio_sample_rate);
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_CHANNEL_COUNT, (int32_t)decoder->recording_audio_channels);
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, decoder->recording_audio_channels == 1 ? 96000 : 128000);
            AMediaFormat_setInt32(format, "aac-profile", 2); /* AAC-LC */
            AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_MAX_INPUT_SIZE, 32768);
            media_status_t configured = AMediaCodec_configure(audio_encoder, format, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
            AMediaFormat_delete(format);
            if(configured != AMEDIA_OK || AMediaCodec_start(audio_encoder) != AMEDIA_OK)
                failed = true;
            else
                encoder_started = true;
        }
    }
    if(failed)
        CHIAKI_LOGE(decoder->log, "PS Link recorder could not initialize AAC audio encoder or MP4 muxer");

    while(!failed)
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

        if(frame.is_audio)
        {
            if(!pslink_feed_pcm(decoder, audio_encoder, &state, &frame, recording_start_us))
                failed = true;
        }
        else if(frame.key_frame && state.video_track < 0)
        {
            if(pslink_add_video_track(decoder, &state))
            {
                state.pending_video = malloc(frame.size);
                if(!state.pending_video)
                    failed = true;
                else
                {
                    memcpy(state.pending_video, frame.data, frame.size);
                    state.pending_video_size = frame.size;
                    state.pending_video_pts = frame.pts_us;
                    state.timeline_origin_us = frame.pts_us;
                    state.have_timeline_origin = true;
                }
            }
        }
        else if(!state.started && frame.key_frame && !state.pending_video)
        {
            state.pending_video = malloc(frame.size);
            if(!state.pending_video)
                failed = true;
            else
            {
                memcpy(state.pending_video, frame.data, frame.size);
                state.pending_video_size = frame.size;
                state.pending_video_pts = frame.pts_us;
                state.timeline_origin_us = frame.pts_us;
                state.have_timeline_origin = true;
            }
        }
        else if(state.started && !frame.is_audio)
        {
            if(!pslink_write_video_sample(decoder, &state, frame.data, frame.size, frame.pts_us, frame.key_frame))
                failed = true;
        }
        free(frame.data);

        if(!failed && !pslink_drain_audio_encoder(decoder, audio_encoder, &state, false, &eos_seen))
            failed = true;
        if(!failed && !pslink_try_start_muxer(decoder, &state))
            failed = true;
    }

    if(encoder_started && !failed)
    {
        ssize_t input_index = AMediaCodec_dequeueInputBuffer(audio_encoder, 10000);
        if(input_index >= 0)
        {
            uint64_t end_pts = 0;
            pthread_mutex_lock(&decoder->recording_mutex);
            end_pts = pslink_monotonic_us() >= recording_start_us ? pslink_monotonic_us() - recording_start_us : 0;
            pthread_mutex_unlock(&decoder->recording_mutex);
            AMediaCodec_queueInputBuffer(audio_encoder, (size_t)input_index, 0, 0,
                    (int64_t)end_pts, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
            for(unsigned i = 0; i < 300 && !eos_seen; ++i)
            {
                if(!pslink_drain_audio_encoder(decoder, audio_encoder, &state, true, &eos_seen))
                {
                    failed = true;
                    break;
                }
            }
        }
    }

    if(audio_encoder)
    {
        if(encoder_started) AMediaCodec_stop(audio_encoder);
        AMediaCodec_delete(audio_encoder);
    }

    bool muxer_finalized = false;
    if(muxer)
    {
        if(state.started)
        {
            media_status_t stop_status = AMediaMuxer_stop(muxer);
            muxer_finalized = stop_status == AMEDIA_OK;
            if(!muxer_finalized)
                CHIAKI_LOGE(decoder->log, "PS Link recorder failed to finalize MP4 (%d)", (int)stop_status);
        }
        AMediaMuxer_delete(muxer);
    }
    free(state.pending_video);
    if(decoder->recording_fd >= 0)
    {
        close(decoder->recording_fd);
        decoder->recording_fd = -1;
    }
    pthread_mutex_lock(&decoder->recording_mutex);
    decoder->recording_muxer_started = muxer_finalized;
    if(!muxer_finalized || failed)
        decoder->recording_failed = true;
    decoder->recording_requested = false;
    pthread_cond_broadcast(&decoder->recording_cond);
    pthread_mutex_unlock(&decoder->recording_mutex);
    if(muxer_finalized && !failed)
        CHIAKI_LOGI(decoder->log, "PS Link audio/video game-stream MP4 recorder finalized");
    else
        CHIAKI_LOGE(decoder->log, "PS Link recorder stopped without a finalized audio/video MP4");
    return NULL;
}
'''

AUDIO_SUBMIT = r'''
void android_chiaki_video_decoder_audio_sample(AndroidChiakiVideoDecoder *decoder,
        const int16_t *samples, size_t samples_count)
{
    if(!decoder || !samples || samples_count == 0 || samples_count > SIZE_MAX / sizeof(int16_t))
        return;
    size_t size = samples_count * sizeof(int16_t);
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
    memcpy(copy, samples, size);
    size_t tail = decoder->recording_queue_tail;
    decoder->recording_queue[tail].data = copy;
    decoder->recording_queue[tail].size = size;
    decoder->recording_queue[tail].pts_us = pslink_monotonic_us();
    decoder->recording_queue[tail].key_frame = false;
    decoder->recording_queue[tail].is_audio = true;
    decoder->recording_queue_tail = (tail + 1) % PSLINK_RECORD_QUEUE_CAPACITY;
    ++decoder->recording_queue_count;
    pthread_cond_signal(&decoder->recording_cond);
    pthread_mutex_unlock(&decoder->recording_mutex);
}
'''

START_RECORDING = r'''bool android_chiaki_video_decoder_start_recording(AndroidChiakiVideoDecoder *decoder,
        int fd, uint32_t audio_channels, uint32_t audio_sample_rate)
{
    if(!decoder || fd < 0 || audio_channels < 1 || audio_channels > 2
            || audio_sample_rate < 8000 || audio_sample_rate > 192000)
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
    decoder->recording_audio_channels = audio_channels;
    decoder->recording_audio_sample_rate = audio_sample_rate;
    decoder->recording_start_us = pslink_monotonic_us();
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
'''


def patch_video_header() -> None:
    path = CHI / "android/app/src/main/cpp/video-decoder.h"
    src = read(path)
    if MARKER in src:
        return
    src = src.replace("#define PSLINK_RECORD_QUEUE_CAPACITY 32", "#define PSLINK_RECORD_QUEUE_CAPACITY 128")
    src = src.replace("\tbool key_frame;\n} PsLinkRecordFrame;", "\tbool key_frame;\n\tbool is_audio;\n} PsLinkRecordFrame;")
    src = src.replace(
        "\tsize_t recording_csd_size[2];",
        "\tsize_t recording_csd_size[2];\n\tuint32_t recording_audio_channels;\n\tuint32_t recording_audio_sample_rate;\n\tuint64_t recording_start_us;\n\t/* " + MARKER + " */"
    )
    src = src.replace(
        "bool android_chiaki_video_decoder_start_recording(AndroidChiakiVideoDecoder *decoder, int fd);",
        "bool android_chiaki_video_decoder_start_recording(AndroidChiakiVideoDecoder *decoder, int fd, uint32_t audio_channels, uint32_t audio_sample_rate);\nvoid android_chiaki_video_decoder_audio_sample(AndroidChiakiVideoDecoder *decoder, const int16_t *samples, size_t samples_count);"
    )
    if MARKER not in src:
        raise SystemExit("Could not add audio recorder state to video-decoder.h")
    write(path, src)


def patch_audio_decoder() -> None:
    header = CHI / "android/app/src/main/cpp/audio-decoder.h"
    src = read(header)
    if "AndroidChiakiAudioDecoderRecordCallback" not in src:
        src = src.replace(
            "typedef void (*AndroidChiakiAudioDecoderFrameCallback)(int16_t *buf, size_t samples_count, void *user);",
            "typedef void (*AndroidChiakiAudioDecoderFrameCallback)(int16_t *buf, size_t samples_count, void *user);\ntypedef void (*AndroidChiakiAudioDecoderRecordCallback)(int16_t *buf, size_t samples_count, void *user);"
        )
        src = src.replace(
            "\tvoid *cb_user;",
            "\tvoid *cb_user;\n\tAndroidChiakiAudioDecoderRecordCallback record_frame_cb;\n\tvoid *record_frame_cb_user;"
        )
        src = src.replace(
            "\tdecoder->cb_user = user;\n}",
            "\tdecoder->cb_user = user;\n}\n\nstatic inline void android_chiaki_audio_decoder_set_record_cb(AndroidChiakiAudioDecoder *decoder, AndroidChiakiAudioDecoderRecordCallback callback, void *user)\n{\n\tdecoder->record_frame_cb = callback;\n\tdecoder->record_frame_cb_user = user;\n}"
        )
    if "android_chiaki_audio_decoder_set_record_cb" not in src:
        raise SystemExit("Could not add Chiaki audio recorder callback setter")
    write(header, src)

    source = CHI / "android/app/src/main/cpp/audio-decoder.c"
    src = read(source)
    src = src.replace(
        "\tdecoder->frame_cb = NULL;",
        "\tdecoder->frame_cb = NULL;\n\tdecoder->record_frame_cb = NULL;\n\tdecoder->record_frame_cb_user = NULL;"
    )
    old = "\t\t\t\tdecoder->frame_cb((int16_t *)codec_buf, samples_count, decoder->cb_user);"
    new = old + "\n\t\t\t\tif(decoder->record_frame_cb)\n\t\t\t\t\tdecoder->record_frame_cb((int16_t *)codec_buf, samples_count, decoder->record_frame_cb_user);"
    if new in src:
        pass
    elif old in src:
        src = src.replace(old, new, 1)
    else:
        raise SystemExit("Could not connect decoded Chiaki PCM frames to the recorder")
    if "record_frame_cb((int16_t *)codec_buf" not in src:
        raise SystemExit("Chiaki PCM recorder callback was not installed")
    write(source, src)


def patch_video_decoder() -> None:
    path = CHI / "android/app/src/main/cpp/video-decoder.c"
    src = read(path)
    if "static bool pslink_annexb_to_length_prefixed" not in src:
        src = replace_c_function(src, r"static bool pslink_start_muxer\s*\(", MUX_HELPERS,
                                  "video-only muxer helper replacement")
        src = replace_c_function(src, r"static void \*pslink_recording_thread_main\s*\(", WORKER,
                                  "audio/video recorder worker")
    old_inactive = '''    if(!recording_active)
    {
        if(pslink_first_nal_is_parameter_set(decoder, data, size))
            pslink_store_codec_config(decoder, data, size);
        return;
    }'''
    new_inactive = '''    if(!recording_active)
    {
        bool has_parameter_sets = false;
        pslink_analyze_access_unit(decoder, data, size, &has_parameter_sets);
        if(has_parameter_sets)
            pslink_store_codec_config(decoder, data, size);
        return;
    }'''
    if old_inactive in src:
        src = src.replace(old_inactive, new_inactive, 1)
    elif "pslink_analyze_access_unit(decoder, data, size, &has_parameter_sets);" not in src:
        raise SystemExit("Could not make video codec-configuration capture NAL-order independent")

    src = src.replace(
        "decoder->recording_queue[tail].key_frame = pslink_is_key_frame(decoder, data, size);",
        "decoder->recording_queue[tail].key_frame = pslink_is_key_frame(decoder, data, size);\n    decoder->recording_queue[tail].is_audio = false;"
    )
    if "void android_chiaki_video_decoder_audio_sample" not in src:
        marker = "bool android_chiaki_video_decoder_start_recording(AndroidChiakiVideoDecoder *decoder, int fd)"
        if marker not in src:
            raise SystemExit("Could not locate native video recorder start function")
        src = src.replace(marker, AUDIO_SUBMIT + "\n" + marker, 1)
        src = replace_c_function(src, r"bool android_chiaki_video_decoder_start_recording\s*\(", START_RECORDING,
                                 "native audio/video recording start")
    if "recording_audio_channels = audio_channels" not in src:
        raise SystemExit("Native audio/video recorder start patch failed")

    # Initialize audio recorder metadata with the existing recorder mutex/queue state.
    init_match = re.search(r"ChiakiErrorCode\s+android_chiaki_video_decoder_init\s*\([^)]*\)\s*\{(?P<body>.*?)\n\}", src, re.S)
    if not init_match:
        raise SystemExit("Could not locate video decoder initialization")
    body = init_match.group("body")
    if "recording_audio_channels" not in body:
        body = body.replace("decoder->recording_queue_count = 0;",
            "decoder->recording_queue_count = 0;\n\tdecoder->recording_audio_channels = 0;\n\tdecoder->recording_audio_sample_rate = 0;\n\tdecoder->recording_start_us = 0;", 1)
        src = src[:init_match.start("body")] + body + src[init_match.end("body"):]

    write(path, src)


def patch_jni() -> None:
    path = CHI / "android/app/src/main/cpp/chiaki-jni.c"
    src = read(path)
    if "PSLINK_NATIVE_AUDIO_RECORDER_JNI_V1" not in src:
        struct_end = "} AndroidChiakiSession;"
        callback = r'''

/* PSLINK_NATIVE_AUDIO_RECORDER_JNI_V1 */
static void android_chiaki_audio_recording_frame(int16_t *samples, size_t samples_count, void *user)
{
    AndroidChiakiSession *session = user;
    if(session)
        android_chiaki_video_decoder_audio_sample(&session->video_decoder, samples, samples_count);
}
'''
        if struct_end not in src:
            raise SystemExit("Could not locate AndroidChiakiSession for audio recorder JNI")
        src = src.replace(struct_end, struct_end + callback, 1)
        setter_anchor = "android_chiaki_audio_decoder_set_cb(&session->audio_decoder, android_chiaki_audio_output_settings, android_chiaki_audio_output_frame, session->audio_output);"
        if setter_anchor not in src:
            raise SystemExit("Could not connect decoded audio PCM to the PS Link recording queue")
        src = src.replace(setter_anchor, setter_anchor + "\n\tandroid_chiaki_audio_decoder_set_record_cb(&session->audio_decoder, android_chiaki_audio_recording_frame, session);", 1)
        old_call = "android_chiaki_video_decoder_start_recording(&session->video_decoder, (int)fd)"
        new_call = "android_chiaki_video_decoder_start_recording(&session->video_decoder, (int)fd, session->audio_decoder.audio_header.channels, session->audio_decoder.audio_header.rate)"
        if old_call not in src:
            raise SystemExit("Could not pass the active PS5 audio format into the recorder")
        src = src.replace(old_call, new_call, 1)

    # Stop and join the decoded audio output thread before tearing down the video recorder it feeds.
    session_free = re.search(
        r"JNIEXPORT\s+void\s+JNICALL\s+JNI_FCN\(sessionFree\)\s*\([^)]*\)\s*\{(?P<body>.*?)\n\}",
        src, re.S
    )
    if not session_free:
        raise SystemExit("Could not locate JNI sessionFree for recorder shutdown ordering")
    free_body = session_free.group("body")
    old_order = "android_chiaki_video_decoder_fini(&session->video_decoder);\n\tandroid_chiaki_audio_decoder_fini(&session->audio_decoder);"
    new_order = "android_chiaki_audio_decoder_fini(&session->audio_decoder);\n\tandroid_chiaki_video_decoder_fini(&session->video_decoder);"
    if new_order not in free_body:
        if old_order not in free_body:
            raise SystemExit("Could not ensure the audio decoder joins before video recorder teardown")
        free_body = free_body.replace(old_order, new_order, 1)
        src = src[:session_free.start("body")] + free_body + src[session_free.end("body"):]
    write(path, src)


def main() -> None:
    if not CHI.is_dir():
        raise SystemExit(f"Chiaki source tree not found: {CHI}; run scripts/bootstrap.sh first")
    patch_video_header()
    patch_audio_decoder()
    patch_video_decoder()
    patch_jni()
    print("PS Link native game-stream recorder now muxes AAC game audio with converted MP4 video samples.")
    print("Capture source: Chiaki decoded game audio + original encoded PS5 video; microphone capture is not used.")


if __name__ == "__main__":
    main()
