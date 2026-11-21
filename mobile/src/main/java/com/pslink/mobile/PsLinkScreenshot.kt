// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

import android.app.Activity
import android.content.ContentValues
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PsLinkScreenshot {
    fun capture(activity: Activity, surface: SurfaceView, onResult: (Boolean, String) -> Unit) {
        if (!surface.isAttachedToWindow || surface.width <= 0 || surface.height <= 0 || !surface.holder.surface.isValid) {
            onResult(false, "سطح عرض اللعبة غير جاهز بعد")
            return
        }
        val scale = minOf(1f, 1920f / surface.width.toFloat())
        val bitmap = Bitmap.createBitmap((surface.width * scale).toInt().coerceAtLeast(1), (surface.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surface, bitmap, { result ->
            if (activity.isFinishing || activity.isDestroyed) {
                bitmap.recycle()
                return@request
            }
            if (result != PixelCopy.SUCCESS) {
                bitmap.recycle()
                onResult(false, "تعذر التقاط إطار اللعبة (PixelCopy: $result)")
                return@request
            }
            try {
                val name = "PSLink_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
                val saved = saveBitmap(activity, bitmap, name)
                onResult(saved, if (saved) "تم حفظ لقطة الشاشة في Pictures/PS Link" else "تعذر حفظ لقطة الشاشة")
            } catch (error: Exception) {
                onResult(false, "تعذر حفظ الصورة: ${error.localizedMessage ?: "خطأ ملفات"}")
            } finally {
                bitmap.recycle()
            }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
        } catch (error: Exception) {
            if (!bitmap.isRecycled) bitmap.recycle()
            onResult(false, "تعذر بدء التقاط الصورة: ${error.localizedMessage ?: "سطح العرض غير جاهز"}")
        }
    }

    private fun saveBitmap(activity: Activity, bitmap: Bitmap, name: String): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PS Link")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            return try {
                val written = activity.contentResolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) } ?: false
                if (written) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    activity.contentResolver.update(uri, values, null, null)
                    true
                } else {
                    activity.contentResolver.delete(uri, null, null)
                    false
                }
            } catch (error: Exception) {
                activity.contentResolver.delete(uri, null, null)
                throw error
            }
        }
        val directory = activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return false
        val output = File(directory, name)
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        MediaScannerConnection.scanFile(activity, arrayOf(output.absolutePath), arrayOf("image/jpeg"), null)
        return output.length() > 0
    }
}
