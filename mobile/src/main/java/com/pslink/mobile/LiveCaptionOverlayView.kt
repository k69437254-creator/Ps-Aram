// SPDX-License-Identifier: AGPL-3.0-only
package com.pslink.mobile

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

/**
 * Draws translated game captions directly over the OCR bounds of the original subtitle.
 * Coordinates are normalized against the Chiaki SurfaceView, so aspect-ratio changes are handled.
 */
class LiveCaptionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var targetSurface: View? = null
    private var captions: List<LiveGameTranslator.CaptionLine> = emptyList()

    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(7, 14, 27)
        alpha = 246
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(82, 151, 255)
        alpha = 210
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(91, 158, 255)
        alpha = 245
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textSize = 23f * density
        isSubpixelText = true
    }

    fun setCaptions(surface: View, lines: List<LiveGameTranslator.CaptionLine>) {
        targetSurface = surface
        captions = lines.take(3)
        visibility = if (captions.isEmpty()) GONE else VISIBLE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val surface = targetSurface ?: return
        if (surface.width <= 0 || surface.height <= 0 || width <= 0 || height <= 0) return

        val surfaceLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        surface.getLocationOnScreen(surfaceLocation)
        getLocationOnScreen(overlayLocation)
        val offsetX = (surfaceLocation[0] - overlayLocation[0]).toFloat()
        val offsetY = (surfaceLocation[1] - overlayLocation[1]).toFloat()
        val surfaceWidth = surface.width.toFloat()
        val surfaceHeight = surface.height.toFloat()

        captions.forEach { line ->
            val sourceLeft = offsetX + line.left * surfaceWidth
            val sourceTop = offsetY + line.top * surfaceHeight
            val sourceRight = offsetX + line.right * surfaceWidth
            val sourceBottom = offsetY + line.bottom * surfaceHeight
            val sourceCenterX = (sourceLeft + sourceRight) / 2f
            val sourceCenterY = (sourceTop + sourceBottom) / 2f

            // Expand around the source line so longer Arabic text fits and the English glyphs are hidden.
            val boxWidth = min(surfaceWidth * 0.90f, max(sourceRight - sourceLeft + 34f * density, surfaceWidth * 0.54f))
            val boxHeight = min(surfaceHeight * 0.18f, max(sourceBottom - sourceTop + 24f * density, 54f * density))
            val box = RectF(
                (sourceCenterX - boxWidth / 2f).coerceAtLeast(offsetX + 4f * density),
                (sourceCenterY - boxHeight / 2f).coerceAtLeast(offsetY + 4f * density),
                (sourceCenterX + boxWidth / 2f).coerceAtMost(offsetX + surfaceWidth - 4f * density),
                (sourceCenterY + boxHeight / 2f).coerceAtMost(offsetY + surfaceHeight - 4f * density)
            )
            if (box.width() <= 0f || box.height() <= 0f) return@forEach

            val radius = 12f * density
            canvas.drawRoundRect(box, radius, radius, backgroundPaint)
            canvas.drawRoundRect(box, radius, radius, borderPaint)
            canvas.drawRoundRect(
                RectF(box.left + 12f * density, box.top + 10f * density, box.left + 15f * density, box.bottom - 10f * density),
                2f * density, 2f * density, accentPaint
            )

            val horizontalPadding = 28f * density
            val verticalPadding = 9f * density
            val contentWidth = (box.width() - horizontalPadding * 2f).toInt().coerceAtLeast(24)
            val availableHeight = box.height() - verticalPadding * 2f
            textPaint.textSize = min(24f * density, max(16f * density, availableHeight * 0.47f))
            val layout = StaticLayout.Builder
                .obtain(line.translated, 0, line.translated.length, textPaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setTextDirection(TextDirectionHeuristics.RTL)
                .setIncludePad(false)
                .setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
            val textTop = box.centerY() - layout.height / 2f
            canvas.save()
            canvas.translate(box.left + horizontalPadding, textTop)
            layout.draw(canvas)
            canvas.restore()
        }
    }
}
