#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Install the PS Link touch-input, clean stream UI, translation and recorder fixes.

Run this file from the PS-Link repository root on branch
feature/platform-completion-pack. It does not commit, push, switch branches or delete files.
It creates timestamped backups before changing tracked project files.
"""
from __future__ import annotations

import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Callable

EXPECTED_BRANCH = "feature/platform-completion-pack"
INSTALL_MARKER = "PSLINK_COMPLETE_FIX_AUTORUN_V1"
RUNTIME_MARKER = "PSLINK_COMPLETE_RUNTIME_FIX_V1"
# PSLINK_BACKUPS_OUTSIDE_ANDROID_RES_V1


def locate_root() -> Path:
    here = Path(__file__).resolve()
    if here.parent.name == "scripts" and (here.parent.parent / "scripts/prepare_pslink_upstream.py").is_file():
        return here.parent.parent
    cwd = Path.cwd().resolve()
    if (cwd / "scripts/prepare_pslink_upstream.py").is_file():
        return cwd
    for parent in here.parents:
        if (parent / "scripts/prepare_pslink_upstream.py").is_file():
            return parent
    raise SystemExit(
        "لم أجد جذر مستودع PS-Link. افتح Termux وادخل مجلد PS-Link ثم شغّل الملف من هناك."
    )


def run_git(root: Path, *args: str) -> str:
    result = subprocess.run(
        ["git", *args], cwd=root, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=False
    )
    if result.returncode != 0:
        raise SystemExit(f"Git command failed: git {' '.join(args)}\n{result.stderr.strip()}")
    return result.stdout.strip()


def relocate_legacy_resource_backups(root: Path) -> list[Path]:
    """Move old PS Link backups/temp files out of Android res trees, preserving bytes."""
    resource_roots = (
        root / "mobile/src/main/res",
        root / "tv/app/src/main/res",
        root / "third_party/chiaki-ng/android/app/src/main/res",
    )
    candidates: set[Path] = set()
    for resource_root in resource_roots:
        if not resource_root.is_dir():
            continue
        for pattern in ("*.before-pslink-*.bak", "*.pslink-tmp-*"):
            candidates.update(path for path in resource_root.rglob(pattern) if path.is_file())

    moved: list[Path] = []
    for source_path in sorted(candidates):
        relative = source_path.relative_to(root)
        destination_dir = root / ".pslink-backups" / relative.parent
        destination_dir.mkdir(parents=True, exist_ok=True)
        destination = destination_dir / source_path.name
        if destination.exists():
            # Never overwrite an earlier backup; retain both copies under distinct names.
            destination = destination_dir / f"{source_path.name}.migrated-{time.time_ns()}"
        source_path.replace(destination)
        moved.append(destination)
    return moved


def write_transaction(root: Path, changes: dict[Path, str]) -> list[Path]:
    """Write a prepared set atomically; keep backups outside Android resource directories."""
    stamp = time.strftime("%Y%m%d-%H%M%S")
    backups: list[Path] = []
    staged: list[tuple[Path, Path, int]] = []
    root_resolved = root.resolve()
    try:
        for path, content in changes.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.exists():
                old = path.read_bytes()
                old_mode = path.stat().st_mode & 0o777
                if old == content.encode("utf-8"):
                    continue
                try:
                    relative = path.resolve().relative_to(root_resolved)
                except ValueError:
                    raise SystemExit(f"Refusing to back up a file outside the project root: {path}")
                backup_dir = root / ".pslink-backups" / relative.parent
                backup_dir.mkdir(parents=True, exist_ok=True)
                backup = backup_dir / (path.name + f".before-pslink-complete-fix-{stamp}.bak")
                if backup.exists():
                    raise SystemExit(f"Backup path already exists; refusing to overwrite: {backup}")
                backup.write_bytes(old)
                os.chmod(backup, old_mode)
                backups.append(backup)
            else:
                old_mode = 0o644

            fd, temporary_name = tempfile.mkstemp(prefix=path.name + ".pslink-tmp-", dir=str(path.parent))
            temporary = Path(temporary_name)
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as stream:
                stream.write(content)
                stream.flush()
                os.fsync(stream.fileno())
            os.chmod(temporary, old_mode)
            staged.append((path, temporary, old_mode))

        for path, temporary, _mode in staged:
            temporary.replace(path)
        return backups
    except Exception:
        for _path, temporary, _mode in staged:
            try:
                temporary.unlink(missing_ok=True)
            except Exception:
                pass
        raise

def read_text(path: Path) -> str:
    if not path.is_file():
        raise SystemExit(f"الملف المطلوب غير موجود: {path}")
    return path.read_text(encoding="utf-8")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    if new in source:
        return source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"Anchor mismatch ({label}): expected 1 occurrence, found {count}")
    return source.replace(old, new, 1)


def replace_kotlin_function(source: str, declaration: str, replacement: str, label: str) -> str:
    """Replace one Kotlin function body with brace matching, preserving surrounding source."""
    matches = list(re.finditer(re.escape(declaration), source))
    if len(matches) != 1:
        raise SystemExit(f"Cannot locate {label}: expected one declaration, found {len(matches)}")
    start = matches[0].start()
    brace = source.find("{", matches[0].end())
    if brace < 0:
        raise SystemExit(f"Cannot locate body for {label}")
    depth = 0
    i = brace
    in_string = False
    triple = False
    in_char = False
    escaped = False
    line_comment = False
    block_comment = False
    while i < len(source):
        c = source[i]
        n = source[i + 1] if i + 1 < len(source) else ""
        if line_comment:
            if c == "\n":
                line_comment = False
        elif block_comment:
            if c == "*" and n == "/":
                block_comment = False
                i += 1
        elif in_string:
            if triple:
                if source.startswith('"""', i):
                    in_string = False
                    triple = False
                    i += 2
            elif escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == '"':
                in_string = False
        elif in_char:
            if escaped:
                escaped = False
            elif c == "\\":
                escaped = True
            elif c == "'":
                in_char = False
        else:
            if c == "/" and n == "/":
                line_comment = True
                i += 1
            elif c == "/" and n == "*":
                block_comment = True
                i += 1
            elif c == '"':
                if source.startswith('"""', i):
                    in_string = True
                    triple = True
                    i += 2
                else:
                    in_string = True
            elif c == "'":
                in_char = True
            elif c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    if end < len(source) and source[end] == "\r":
                        end += 1
                    if end < len(source) and source[end] == "\n":
                        end += 1
                    return source[:start] + replacement.rstrip() + "\n" + source[end:]
        i += 1
    raise SystemExit(f"Unclosed Kotlin function body while patching {label}")


def patch_control_editor(source: str) -> str:
    # Restore the saved alpha value after the fragment view is recreated.
    source = replace_once(
        source,
        "                view.layoutParams = lp\n            }\n        }\n    }\n\n    private fun createTouchListener",
        "                view.layoutParams = lp\n            }\n"
        "            if (prefs.contains(\"alpha_$id\")) {\n"
        "                view.alpha = prefs.getInt(\"alpha_$id\", 100).coerceIn(20, 100) / 100f\n"
        "            }\n"
        "        }\n    }\n\n    private fun createTouchListener",
        "restore saved opacity in ControlLayoutEditor",
    )
    source = replace_once(
        source,
        'val entries = mutableListOf("تكبير العنصر", "تصغير العنصر", "إعادة موضعه وحجمه الأصلي")',
        'val entries = mutableListOf("تكبير العنصر", "تصغير العنصر", "إعادة موضعه وحجمه الأصلي", "تعديل الشفافية")',
        "add opacity option to touch-control editor",
    )
    source = replace_once(
        source,
        '                    "تصغير العنصر" -> resize(view, 0.88f)\n',
        '                    "تصغير العنصر" -> resize(view, 0.88f)\n'
        '                    "تعديل الشفافية" -> changeOpacity(view)\n',
        "wire opacity option",
    )
    source = replace_once(
        source,
        '    private fun resize(view: View, scale: Float) {',
        r'''    private fun changeOpacity(view: View) {
        val previous = (view.alpha * 100f).roundToInt().coerceIn(20, 100)
        val slider = android.widget.SeekBar(context).apply {
            max = 100
            progress = previous
        }
        slider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                view.alpha = value.coerceIn(20, 100) / 100f
            }
            override fun onStartTrackingTouch(bar: android.widget.SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: android.widget.SeekBar?) = Unit
        })
        val dialog = AlertDialog.Builder(context)
            .setTitle("شفافية عنصر التحكم")
            .setMessage("حرّك المؤشر لاختيار شفافية بين 20٪ و100٪")
            .setView(slider)
            .setPositiveButton("حفظ", null)
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("استعادة الأصل", null)
            .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val selected = slider.progress.coerceIn(20, 100)
                view.alpha = selected / 100f
                prefs.edit().putInt("alpha_${view.id}", selected).apply()
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                view.alpha = previous / 100f
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                view.alpha = 1f
                prefs.edit().remove("alpha_${view.id}").apply()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun resize(view: View, scale: Float) {''',
        "install per-control opacity slider",
    )
    source = replace_once(
        source,
        '        view.layoutParams = ConstraintLayout.LayoutParams(original)\n'
        '        prefs.edit().remove("x_${view.id}").remove("y_${view.id}").remove("w_${view.id}").remove("h_${view.id}").apply()\n',
        '        view.layoutParams = ConstraintLayout.LayoutParams(original)\n'
        '        view.alpha = 1f\n'
        '        prefs.edit().remove("x_${view.id}").remove("y_${view.id}").remove("w_${view.id}").remove("h_${view.id}").remove("alpha_${view.id}").apply()\n',
        "reset opacity with control frame",
    )
    return source


def patch_translator(source: str) -> str:
    source = replace_once(
        source,
        '    private var emptyFrameCount = 0\n',
        '    private var emptyFrameCount = 0\n    private var consecutiveCaptureFailures = 0\n',
        "track transient PixelCopy capture failures",
    )
    source = replace_once(
        source,
        '''        if (enabled && selected.code != language.code) pause()
        language = selected
''',
        '''        if (selected.code != language.code) {
            pause()
            lastSource = ""
            lastTranslation = ""
            emptyFrameCount = 0
            consecutiveCaptureFailures = 0
            onCaption(emptyList())
        }
        language = selected
''',
        "clear cached dialogue when the source language changes",
    )
    source = replace_once(
        source,
        '''    fun stop() {
        tvClient?.sendCaptions(emptyList(), System.nanoTime())
        enabled = false
        pause()
''',
        '''    fun stop() {
        enabled = false
        pause()
''',
        "avoid sending duplicate clear packets when translation stops",
    )
    source = replace_once(
        source,
        '                                    tvClient?.sendCaptions(emptyList(), samplePtsNs)\n'
        '                                    tvClient?.sendCaptions(emptyList(), samplePtsNs)\n',
        '                                    tvClient?.sendCaptions(emptyList(), samplePtsNs)\n',
        "remove duplicate empty-caption packet",
    )
    source = replace_once(
        source,
        '''                    val bitmap = captureFrame()
                    if (bitmap != null) {
                        try {
''',
        '''                    val bitmap = captureFrame()
                    if (bitmap == null) {
                        consecutiveCaptureFailures++
                        if (consecutiveCaptureFailures == 5) {
                            onStatus("تعذر التقاط إطارات اللعبة من سطح البث. تأكد من أن بث PS5 ظاهر ثم أعد تشغيل الترجمة.")
                        }
                    } else {
                        consecutiveCaptureFailures = 0
                        try {
''',
        "report repeated SurfaceView capture failures without killing the OCR loop",
    )
    source = replace_once(
        source,
        '        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0) return@withContext null\n',
        '        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) return@withContext null\n',
        "validate the actual Chiaki SurfaceView surface before PixelCopy",
    )
    source = replace_once(
        source,
        '''            } catch (error: Exception) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
''',
        '''            } catch (_: Exception) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resume(null)
            }
''',
        "treat transient PixelCopy errors as retryable capture misses",
    )
    source = replace_once(
        source,
        "        emptyFrameCount = 0\n        onCaption(emptyList())",
        "        emptyFrameCount = 0\n        consecutiveCaptureFailures = 0\n        onCaption(emptyList())",
        "reset capture-failure counter when translation stops",
    )
    return source


VIDEO_TRIM_FUNCTION = r'''    private fun trimVideo(startUs: Long, endUs: Long): Uri {
        val extractor = MediaExtractor()
        val temp = File(cacheDir, "pslink_trim_${System.currentTimeMillis()}.mp4")
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(this, videoUri, null)
            val sourceTracks = (0 until extractor.trackCount).filter { track ->
                val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/") || mime.startsWith("audio/")
            }
            val videoTracks = sourceTracks.filter { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            if (videoTracks.isEmpty()) throw IllegalStateException("لا يحتوي التسجيل على مسار فيديو")
            if (sourceTracks.isEmpty()) throw IllegalStateException("لا توجد مسارات فيديو أو صوت قابلة للقص")

            val activeMuxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            val outputTracks = HashMap<Int, Int>()
            sourceTracks.forEach { inputTrack ->
                outputTracks[inputTrack] = activeMuxer.addTrack(extractor.getTrackFormat(inputTrack))
                extractor.selectTrack(inputTrack)
            }
            activeMuxer.start()
            muxerStarted = true
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val requestedSize = sourceTracks.maxOfOrNull { track ->
                val format = extractor.getTrackFormat(track)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                    format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1)
                else 4 shl 20
            } ?: (4 shl 20)
            val maxSize = requestedSize.coerceIn(1 shl 20, 32 shl 20)
            val buffer = ByteBuffer.allocateDirect(maxSize)
            val info = android.media.MediaCodec.BufferInfo()
            var firstVideoPts = -1L
            var videoSamples = 0
            while (true) {
                buffer.clear()
                val inputTrack = extractor.sampleTrackIndex
                if (inputTrack < 0) break
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val pts = extractor.sampleTime
                if (pts > endUs) break
                val isVideoTrack = inputTrack in videoTracks
                if (isVideoTrack && firstVideoPts < 0) firstVideoPts = pts
                // Audio samples before the first copied video key frame must not
                // shift the MP4 timeline or create negative/duplicate timestamps.
                if (firstVideoPts < 0 || (!isVideoTrack && pts < firstVideoPts)) {
                    if (!extractor.advance()) break
                    continue
                }
                val outputTrack = outputTracks[inputTrack]
                    ?: throw IllegalStateException("ظهر مسار غير محدد أثناء قص الفيديو")
                info.set(0, size, (pts - firstVideoPts).coerceAtLeast(0), extractor.sampleFlags)
                buffer.position(0)
                buffer.limit(size)
                activeMuxer.writeSampleData(outputTrack, buffer, info)
                if (isVideoTrack) videoSamples++
                if (!extractor.advance()) break
            }
            if (videoSamples == 0) throw IllegalStateException("لم توجد إطارات فيديو داخل النطاق المحدد")
            activeMuxer.stop()
            muxerStarted = false
            activeMuxer.release()
            muxer = null
            return publishEditedFile(temp)
        } finally {
            val remainingMuxer = muxer
            if (remainingMuxer != null) {
                if (muxerStarted) runCatching { remainingMuxer.stop() }
                runCatching { remainingMuxer.release() }
            }
            extractor.release()
            temp.delete()
        }
    }'''


def patch_video_editor(source: str) -> str:
    return replace_kotlin_function(
        source,
        "    private fun trimVideo(startUs: Long, endUs: Long): Uri",
        VIDEO_TRIM_FUNCTION,
        "VideoEditorActivity.trimVideo",
    )


def patch_mobile_sources(root: Path) -> dict[Path, str]:
    changes: dict[Path, str] = {}
    control_path = root / "mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt"
    translator_path = root / "mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
    editor_path = root / "mobile/src/main/java/com/pslink/mobile/capture/VideoEditorActivity.kt"
    changes[control_path] = patch_control_editor(read_text(control_path))
    changes[translator_path] = patch_translator(read_text(translator_path))
    changes[editor_path] = patch_video_editor(read_text(editor_path))

    test_path = root / "mobile/src/test/java/com/metallic/chiaki/touchcontrols/ControllerStateMergeTest.kt"
    test_source = r'''// SPDX-License-Identifier: LicenseRef-AGPL-3.0-only-OpenSSL
package com.metallic.chiaki.touchcontrols

import com.metallic.chiaki.lib.ControllerState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ControllerStateMergeTest {
    @Test
    fun everyControlsFragmentContributesToTheSessionState() {
        val onScreenControls = MutableStateFlow(
            ControllerState(buttons = ControllerState.BUTTON_CROSS, leftX = 1000.toShort())
        )
        val touchpadControls = MutableStateFlow(
            ControllerState(buttons = ControllerState.BUTTON_TOUCHPAD)
        )
        val result = runBlocking {
            mergeControllerStates(listOf(onScreenControls, touchpadControls)).first()
        }
        assertEquals(ControllerState.BUTTON_CROSS or ControllerState.BUTTON_TOUCHPAD, result.buttons)
        assertEquals(1000.toShort(), result.leftX)
    }

    @Test
    fun copiedControllerStateOwnsItsTouchObjects() {
        val original = ControllerState()
        val copy = original.copy()
        original.startTouch(100U, 200U)
        assertEquals((-1).toByte(), copy.touches[0].id)
    }

    @Test
    fun touchMoveChangesThePublishedStateValue() {
        val original = ControllerState()
        val touchId = original.startTouch(100U, 200U)!!
        val beforeMove = original.copy()
        original.setTouchPos(touchId, 300U, 400U)
        assertNotEquals(beforeMove, original.copy())
    }
}
'''
    if test_path.exists():
        existing_test = read_text(test_path)
        if "class ControllerStateMergeTest" not in existing_test:
            raise SystemExit(f"يوجد ملف اختبار مختلف في المسار نفسه؛ لم أستبدله: {test_path}")
    else:
        changes[test_path] = test_source

    return changes


def patch_check_script(source: str) -> str:
    check_block = r'''

# PSLINK_COMPLETE_RUNTIME_FIX_V1 — regressions that previously escaped the static checks.
CHI_STREAM="$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
CHI_CONTROLS="$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/touchcontrols/TouchControlsFragment.kt"
CHI_STATE="$ROOT/third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/lib/Chiaki.kt"
CHI_DECODER="$ROOT/third_party/chiaki-ng/android/app/src/main/cpp/video-decoder.c"
[[ -f "$CHI_STREAM" && -f "$CHI_CONTROLS" && -f "$CHI_STATE" && -f "$CHI_DECODER" ]] || {
  echo "PS Link complete-fix source files are missing after bootstrap." >&2
  exit 1
}
grep -Fq 'controlsSources' "$CHI_STREAM"
grep -Fq 'mergeControllerStates(controlsSources.toList())' "$CHI_STREAM"
grep -Fq 'fun mergeControllerStates(sources: List<Flow<ControllerState>>)' "$CHI_CONTROLS"
grep -Fq 'touches = touches.map { it.copy() }.toTypedArray()' "$CHI_STATE"
grep -Fq 'psLinkMenuButton' "$CHI_STREAM"
grep -Fq 'PSLINK_CLEAN_STREAM_OVERLAY_V1' "$CHI_STREAM"
grep -Fq 'psLinkStartGameRecording()' "$CHI_STREAM"
grep -Fq 'startVideoRecording(openedDescriptor.fd)' "$CHI_STREAM"
grep -Fq 'stopVideoRecording()' "$CHI_STREAM"
grep -Fq 'PSLINK_NATIVE_STREAM_RECORDER_V1' "$CHI_DECODER"
grep -Fq 'PSLINK_NATIVE_AUDIO_RECORDER_JNI_V1' "$ROOT/third_party/chiaki-ng/android/app/src/main/cpp/chiaki-jni.c"
if grep -Eq 'MediaProjectionManager|ScreenRecordingService' "$CHI_STREAM"; then
  echo "Active stream screen still references the phone-display recorder; expected native Chiaki game-stream recording." >&2
  exit 1
fi
grep -Fq 'تعديل الشفافية' "$ROOT/mobile/src/main/java/com/metallic/chiaki/touchcontrols/ControlLayoutEditor.kt"
grep -Fq 'consecutiveCaptureFailures' "$ROOT/mobile/src/main/java/com/pslink/mobile/LiveGameTranslator.kt"
grep -Fq 'val sourceTracks = (0 until extractor.trackCount)' "$ROOT/mobile/src/main/java/com/pslink/mobile/capture/VideoEditorActivity.kt"
grep -Fq 'class ControllerStateMergeTest' "$ROOT/mobile/src/test/java/com/metallic/chiaki/touchcontrols/ControllerStateMergeTest.kt"
echo "PS Link controller, translation, game-stream recording, audio-preserving trim and clean-menu checks passed."
'''
    if RUNTIME_MARKER in source:
        return source
    if not source.rstrip().endswith('echo "PS Link feature static checks passed. Gradle compilation and live PS5/TV hardware tests are still required."'):
        raise SystemExit("Unexpected check-pslink-features.sh ending; refusing to append checks")
    return source.rstrip() + check_block + "\n"


def patch_check_source(source: str) -> str:
    script_name = "scripts/prepare_pslink_complete_fix.py"
    if script_name in source:
        return source
    anchor = "scripts/prepare_pslink_audio_recording.py"
    if source.count(anchor) != 1:
        raise SystemExit("check-source.sh: could not uniquely locate the py_compile script list")
    return source.replace(anchor, anchor + " " + script_name, 1)


def patch_workflow(source: str) -> str:
    if "Run PS Link controller regression tests" in source:
        return source
    anchor = "      - name: Build PS Link Mobile APK\n"
    addition = (
        "      - name: Run PS Link controller regression tests\n"
        "        run: bash ./gradlew --no-daemon --stacktrace :mobile:testDebugUnitTest\n\n"
    )
    return replace_once(source, anchor, addition + anchor, "run mobile controller regression tests in CI")


def patch_persistent_bootstrap(source: str) -> str:
    if INSTALL_MARKER in source:
        return source
    required_tail = (
        "_pslink_live_tv_runpy.run_path(str(_pslink_live_tv_script), run_name=\"__main__\")"
    )
    if not source.rstrip().endswith(required_tail):
        raise SystemExit("prepare_pslink_upstream.py ending differs from the inspected branch; refusing to attach runtime patch")
    addition = f'''

# {INSTALL_MARKER}
# Reapply the PS Link complete fix after Chiaki-NG has been reset to its pinned commit
# and after the recorder/live-TV overlays have finished patching StreamActivity.
import pathlib as _pslink_complete_pathlib
import subprocess as _pslink_complete_subprocess
import sys as _pslink_complete_sys
_pslink_complete_script = _pslink_complete_pathlib.Path(__file__).resolve().parent / "prepare_pslink_complete_fix.py"
if not _pslink_complete_script.is_file():
    raise SystemExit(f"Missing PS Link complete-fix overlay: {{_pslink_complete_script}}")
_pslink_complete_subprocess.run([_pslink_complete_sys.executable, str(_pslink_complete_script)], check=True)
'''
    return source.rstrip() + addition + "\n"


def patch_runtime_upstream(root: Path) -> dict[Path, str]:
    up = root / "third_party/chiaki-ng/android/app/src/main"
    stream_path = up / "java/com/metallic/chiaki/stream/StreamActivity.kt"
    controls_path = up / "java/com/metallic/chiaki/touchcontrols/TouchControlsFragment.kt"
    state_path = up / "java/com/metallic/chiaki/lib/Chiaki.kt"
    layout_path = up / "res/layout/activity_stream.xml"
    stream = read_text(stream_path)
    merge_controller_import = "import com.metallic.chiaki.touchcontrols.mergeControllerStates\n"
    if merge_controller_import.strip() not in stream:
        stream = replace_once(
            stream,
            "import com.metallic.chiaki.touchcontrols.TouchControlsFragment\n",
            "import com.metallic.chiaki.touchcontrols.TouchControlsFragment\n"
            + merge_controller_import,
            "import mergeControllerStates from touchcontrols package",
        )
    controls = read_text(controls_path)
    state = read_text(state_path)
    layout = read_text(layout_path)

    # 1) Collect input from BOTH touch-control fragments instead of cancelling one source.
    if "private val controlsSources = mutableListOf<Flow<ControllerState>>()" not in stream:
        stream = replace_once(
            stream,
            "import com.metallic.chiaki.lib.ConnectVideoProfile\n",
            "import com.metallic.chiaki.lib.ConnectVideoProfile\nimport com.metallic.chiaki.lib.ControllerState\n",
            "import ControllerState for merged touch sources",
        )
        stream = replace_once(
            stream,
            "import kotlinx.coroutines.Job\n",
            "import kotlinx.coroutines.Job\nimport kotlinx.coroutines.flow.Flow\n",
            "import Flow for merged touch sources",
        )
        old_controls_block = '''\tprivate var controlsJob: Job? = null

\toverride fun onAttachFragment(fragment: Fragment)
\t{
\t\tsuper.onAttachFragment(fragment)
\t\tif(fragment is TouchControlsFragment)
\t\t{
\t\t\tcontrolsJob?.cancel()
\t\t\tcontrolsJob = fragment.controllerState
\t\t\t\t.onEach { viewModel.input.touchControllerState = it }
\t\t\t\t.launchIn(lifecycleScope)
\t\t\tfragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
\t\t\tif(fragment is TouchpadOnlyFragment)
\t\t\t\tfragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
\t\t}
\t}
'''
        new_controls_block = '''\tprivate val controlsSources = mutableListOf<Flow<ControllerState>>()
\tprivate var controlsJob: Job? = null

\toverride fun onAttachFragment(fragment: Fragment)
\t{
\t\tsuper.onAttachFragment(fragment)
\t\tif(fragment is TouchControlsFragment)
\t\t{
\t\t\t// Both fragments are mounted by activity_stream.xml; neither source may replace the other.
\t\t\tcontrolsSources.add(fragment.controllerState)
\t\t\tcontrolsJob?.cancel()
\t\t\tcontrolsJob = mergeControllerStates(controlsSources.toList())
\t\t\t\t.onEach { viewModel.input.touchControllerState = it }
\t\t\t\t.launchIn(lifecycleScope)
\t\t\tfragment.onScreenControlsEnabled = viewModel.onScreenControlsEnabled
\t\t\tif(fragment is TouchpadOnlyFragment)
\t\t\t\tfragment.touchpadOnlyEnabled = viewModel.touchpadOnlyEnabled
\t\t}
\t}
'''
        stream = replace_once(stream, old_controls_block, new_controls_block, "merge all attached touch-control fragments")
    elif "mergeControllerStates(controlsSources.toList())" not in stream:
        raise SystemExit("StreamActivity has a partial controlsSources fix; refusing to guess its state")

    # 2) Combine button/stick and touchpad flows without losing either one.
    if "fun mergeControllerStates(sources: List<Flow<ControllerState>>): Flow<ControllerState>" not in controls:
        controls = replace_once(
            controls,
            "abstract class TouchControlsFragment : Fragment()\n",
            "fun mergeControllerStates(sources: List<Flow<ControllerState>>): Flow<ControllerState> =\n"
            "\tcombine(sources) { states -> states.reduce { acc, state -> acc or state } }\n\n"
            "abstract class TouchControlsFragment : Fragment()\n",
            "install source-flow merge function",
        )

    # 3) ControllerState.copy() must own its touch objects; otherwise StateFlow equality drops moves/lifts.
    deep_copy = "\t\ttouches = touches.map { it.copy() }.toTypedArray()"
    if deep_copy not in state:
        state = replace_once(
            state,
            "\tvar orientW: Float = 1.0f\n){\n\tcompanion object",
            "\tvar orientW: Float = 1.0f\n){\n\tinit\n\t{\n" + deep_copy + "\n\t}\n\n\tcompanion object",
            "deep-copy touch points in ControllerState",
        )

    # 4) Add a single, unobtrusive settings icon; the old action bar remains hidden for ViewBinding IDs.
    if "@+id/psLinkMenuButton" not in layout:
        icon = '''
    <ImageButton
        android:id="@+id/psLinkMenuButton"
        android:layout_width="46dp"
        android:layout_height="46dp"
        android:layout_gravity="top|end"
        android:layout_margin="10dp"
        android:background="@drawable/pslink_menu_background"
        android:contentDescription="إعدادات PS Link"
        android:elevation="30dp"
        android:padding="11dp"
        android:scaleType="centerInside"
        android:src="@drawable/pslink_menu_icon" />
'''
        if layout.count("</FrameLayout>") != 1:
            raise SystemExit("activity_stream.xml root is not the expected single FrameLayout")
        layout = layout.replace("</FrameLayout>", icon + "</FrameLayout>", 1)
    ET.fromstring(layout)

    drawable_dir = up / "res/drawable"
    background_path = drawable_dir / "pslink_menu_background.xml"
    icon_path = drawable_dir / "pslink_menu_icon.xml"
    background = '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="oval">
    <solid android:color="#CC07111F" />
    <stroke android:width="1dp" android:color="#668CBFFF" />
</shape>
'''
    vector = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#FFFFFFFF" android:pathData="M12,5.5a1.5,1.5 0,1 0,0 3a1.5,1.5 0,1 0,0 -3M12,10.5a1.5,1.5 0,1 0,0 3a1.5,1.5 0,1 0,0 -3M12,15.5a1.5,1.5 0,1 0,0 3a1.5,1.5 0,1 0,0 -3" />
</vector>
'''

    # Insert menu initialization at a stable point inside setupPsLinkFeatures().
    if "binding.psLinkMenuButton.setOnClickListener { showPsLinkQuickMenu() }" not in stream:
        stream = replace_once(
            stream,
            '\t\tval livePrefs = getSharedPreferences("pslink_live", MODE_PRIVATE)\n',
            '\t\tval livePrefs = getSharedPreferences("pslink_live", MODE_PRIVATE)\n'
            '\t\tbinding.overlay.isGone = true\n'
            '\t\tbinding.overlay.alpha = 0.0f\n'
            '\t\tbinding.psLinkToolsBar.isGone = true\n'
            '\t\tbinding.psLinkCaptionText.isGone = true\n'
            '\t\tbinding.psLinkMenuButton.setOnClickListener { showPsLinkQuickMenu() }\n',
            "hide full toolbar and status text; wire the compact menu icon",
        )

    # Keep Chiaki's legacy overlay hidden. The PS Link menu icon is the sole settings entry.
    if "PSLINK_CLEAN_STREAM_OVERLAY_V1" not in stream:
        stream = replace_kotlin_function(
            stream,
            "\tprivate fun showOverlay()",
            """	private fun showOverlay()
	{
		// PSLINK_CLEAN_STREAM_OVERLAY_V1
		binding.overlay.animate().cancel()
		binding.overlay.alpha = 0.0f
		binding.overlay.isGone = true
		uiVisibilityHandler.removeCallbacks(hideSystemUIRunnable)
		uiVisibilityHandler.postDelayed(hideSystemUIRunnable, HIDE_UI_TIMEOUT_MS)
	}""",
            "keep legacy Chiaki stream options hidden behind the compact PS Link menu",
        )

    # Surface translation failures as a Toast, not as persistent UI text over the game.
    if 'status.startsWith("تعذر التقاط إطارات اللعبة")' not in stream:
        stream = replace_once(
            stream,
            '\t\t\t\tbinding.psLinkCaptionText.text = status\n',
            '\t\t\t\tbinding.psLinkCaptionText.text = status\n'
            '\t\t\t\tif(status.startsWith("توقفت الترجمة الحية") || status.startsWith("تعذر التقاط إطارات اللعبة")) Toast.makeText(this, status, Toast.LENGTH_LONG).show()\n',
            "show translation errors without a permanent overlay label",
        )

    menu_methods = r'''
	private fun showPsLinkQuickMenu()
	{
		val popup = android.widget.PopupMenu(this, binding.psLinkMenuButton)
		popup.menu.add(0, 1, 0, if(psLinkTranslator?.isEnabled == true) "إيقاف الترجمة الحية" else "تشغيل الترجمة الحية")
		popup.menu.add(0, 2, 1, "اختيار لغة الحوار")
		popup.menu.add(0, 3, 2, if(psLinkRecordingActive) "إيقاف تسجيل فيديو اللعبة" else "تسجيل فيديو اللعبة")
		popup.menu.add(0, 4, 3, if(psLinkControlEditMode) "إنهاء تخصيص الأزرار" else "تخصيص الأزرار")
		popup.menu.add(0, 5, 4, "التقاط صورة من اللعبة")
		popup.setOnMenuItemClickListener { item ->
			when(item.itemId)
			{
				1 -> psLinkToggleLiveTranslation()
				2 -> showPsLinkLanguagePicker()
				3 -> if(psLinkRecordingActive) psLinkStopGameRecording() else psLinkStartGameRecording()
				4 -> togglePsLinkControlEditor()
				5 -> PsLinkScreenshot.capture(this, binding.surfaceView) { _, message ->
					Toast.makeText(this, message, Toast.LENGTH_LONG).show()
				}
				else -> return@setOnMenuItemClickListener false
			}
			true
		}
		popup.show()
	}

	private fun psLinkToggleLiveTranslation()
	{
		val engine = psLinkTranslator ?: return
		val prefs = getSharedPreferences("pslink_live", MODE_PRIVATE)
		if(engine.isEnabled)
		{
			engine.stop()
			prefs.edit().putBoolean("enabled", false).apply()
			Toast.makeText(this, "تم إيقاف الترجمة الحية", Toast.LENGTH_SHORT).show()
		}
		else
		{
			val code = prefs.getString("language", com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH).orEmpty()
			startPsLinkLiveTranslation(code)
		}
	}

	private fun showPsLinkLanguagePicker()
	{
		val languages = LiveGameTranslator.languages
		val prefs = getSharedPreferences("pslink_live", MODE_PRIVATE)
		val current = prefs.getString("language", com.google.mlkit.nl.translate.TranslateLanguage.ENGLISH).orEmpty()
		val selected = languages.indexOfFirst { it.code == current }.coerceAtLeast(0)
		AlertDialog.Builder(this)
			.setTitle("لغة الحوار الأصلية")
			.setSingleChoiceItems(languages.map { it.label }.toTypedArray(), selected) { dialog, index ->
				val language = languages[index]
				prefs.edit().putString("language", language.code).putBoolean("enabled", true).apply()
				dialog.dismiss()
				startPsLinkLiveTranslation(language.code)
			}
			.setNegativeButton("إغلاق", null)
			.show()
	}

'''
    if "private fun showPsLinkQuickMenu()" not in stream:
        stream = replace_once(
            stream,
            "\tprivate fun stateChanged(state: StreamState)\n",
            menu_methods + "\tprivate fun stateChanged(state: StreamState)\n",
            "add compact settings menu and translation settings",
        )

    # Ensure native stream recording is active; MediaProjection screen capture is not used.
    required = (
        "private var psLinkRecordingActive",
        "private fun psLinkStartGameRecording()",
        "startVideoRecording(openedDescriptor.fd)",
        "private fun psLinkStopGameRecording()",
        "stopVideoRecording()",
    )
    for marker in required:
        if marker not in stream:
            raise SystemExit(f"Native game-stream recorder was not installed before UI patch: {marker}")
    if "MediaProjectionManager" in stream or "ScreenRecordingService" in stream:
        raise SystemExit("Legacy MediaProjection recorder is still present in StreamActivity; refusing to produce a potentially black-screen capture build")

    return {
        stream_path: stream,
        controls_path: controls,
        state_path: state,
        layout_path: layout,
        background_path: background,
        icon_path: vector,
    }


def apply_runtime(root: Path) -> list[Path]:
    changes = patch_runtime_upstream(root)
    moved_legacy = relocate_legacy_resource_backups(root)
    backups = write_transaction(root, changes)
    if moved_legacy:
        print(f"Moved {len(moved_legacy)} legacy backup/temp file(s) out of Android resource folders.")
    print("Applied runtime overlay: touch inputs merged, controller touch state copied safely, and compact stream menu installed.")
    return moved_legacy + backups


def install_project(root: Path) -> list[Path]:
    branch = run_git(root, "branch", "--show-current")
    if branch != EXPECTED_BRANCH:
        raise SystemExit(
            f"الفرع الحالي هو {branch!r} وليس {EXPECTED_BRANCH!r}. لم أغيّر الفرع ولم أكتب أي ملف."
        )

    # Prepare all source changes in memory first; no files are written until every anchor matches.
    mobile_changes = patch_mobile_sources(root)
    check_features_path = root / "scripts/check-pslink-features.sh"
    check_source_path = root / "scripts/check-source.sh"
    workflow_path = root / ".github/workflows/android.yml"
    prepare_path = root / "scripts/prepare_pslink_upstream.py"

    mobile_changes[check_features_path] = patch_check_script(read_text(check_features_path))
    mobile_changes[check_source_path] = patch_check_source(read_text(check_source_path))
    mobile_changes[workflow_path] = patch_workflow(read_text(workflow_path))
    mobile_changes[prepare_path] = patch_persistent_bootstrap(read_text(prepare_path))

    # Persist this exact file inside scripts/ so bootstrap can reapply the runtime overlay after fetching Chiaki.
    installed_path = root / "scripts/prepare_pslink_complete_fix.py"
    own_bytes = Path(__file__).resolve().read_bytes()
    if installed_path.exists():
        if hashlib.sha256(installed_path.read_bytes()).digest() != hashlib.sha256(own_bytes).digest():
            raise SystemExit(f"يوجد ملف مختلف بالاسم نفسه؛ لم أستبدله: {installed_path}")
    else:
        mobile_changes[installed_path] = own_bytes.decode("utf-8")

    backups = write_transaction(root, mobile_changes)
    print(f"Installed persistent overlay: {installed_path}")
    return backups


def main() -> None:
    root = locate_root()
    inside_scripts = Path(__file__).resolve().parent == (root / "scripts").resolve()
    if inside_scripts and Path(__file__).resolve().name == "prepare_pslink_complete_fix.py":
        # Invoked by bootstrap after the pinned Chiaki source and the existing stream/audio overlays.
        apply_runtime(root)
        return

    backups = install_project(root)
    print("Installed project-level fixes and CI regression checks.")
    runtime_stream = root / "third_party/chiaki-ng/android/app/src/main/java/com/metallic/chiaki/stream/StreamActivity.kt"
    if runtime_stream.is_file() and "private fun psLinkStartGameRecording()" in runtime_stream.read_text(encoding="utf-8"):
        runtime_backups = apply_runtime(root)
        backups.extend(runtime_backups)
    else:
        print("The pinned Chiaki checkout is not fully prepared yet; the runtime touch/menu patch will apply automatically during scripts/bootstrap.sh.")
    print(f"Backups created: {len(backups)}")
    for path in backups:
        print(f"  {path.relative_to(root)}")
    print("No commit, push, branch switch or file deletion was performed.")


if __name__ == "__main__":
    main()
