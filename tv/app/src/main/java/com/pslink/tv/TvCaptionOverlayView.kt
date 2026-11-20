// SPDX-License-Identifier: AGPL-3.0-only
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
