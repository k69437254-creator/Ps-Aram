// SPDX-License-Identifier: AGPL-3.0-only
package com.metallic.chiaki.touchcontrols

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import com.metallic.chiaki.R
import com.metallic.chiaki.lib.ControllerState
import kotlin.math.roundToInt

/** On-screen control layout editor. Position/size and digital button remaps persist locally. */
class ControlLayoutEditor(private val context: Context, private val root: View) {
    companion object {
        private const val PREFS = "pslink_touch_layout"
        private val MAPPABLE = mapOf(
            R.id.crossButtonView to ControllerState.BUTTON_CROSS,
            R.id.moonButtonView to ControllerState.BUTTON_MOON,
            R.id.boxButtonView to ControllerState.BUTTON_BOX,
            R.id.pyramidButtonView to ControllerState.BUTTON_PYRAMID,
            R.id.l1ButtonView to ControllerState.BUTTON_L1,
            R.id.r1ButtonView to ControllerState.BUTTON_R1,
            R.id.l3ButtonView to ControllerState.BUTTON_L3,
            R.id.r3ButtonView to ControllerState.BUTTON_R3,
            R.id.optionsButtonView to ControllerState.BUTTON_OPTIONS,
            R.id.shareButtonView to ControllerState.BUTTON_SHARE,
            R.id.psButtonView to ControllerState.BUTTON_PS
        )
        private val LABELS = listOf("Cross", "Circle", "Square", "Triangle", "D-pad Left", "D-pad Right", "D-pad Up", "D-pad Down", "L1", "R1", "L3", "R3", "Options", "Share", "PS")
        private val MASKS = listOf(
            ControllerState.BUTTON_CROSS, ControllerState.BUTTON_MOON, ControllerState.BUTTON_BOX,
            ControllerState.BUTTON_PYRAMID, ControllerState.BUTTON_DPAD_LEFT, ControllerState.BUTTON_DPAD_RIGHT,
            ControllerState.BUTTON_DPAD_UP, ControllerState.BUTTON_DPAD_DOWN, ControllerState.BUTTON_L1,
            ControllerState.BUTTON_R1, ControllerState.BUTTON_L3, ControllerState.BUTTON_R3,
            ControllerState.BUTTON_OPTIONS, ControllerState.BUTTON_SHARE, ControllerState.BUTTON_PS
        )

        fun mappedMask(context: Context, viewId: Int, fallback: UInt): UInt {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("map_$viewId", -1L)
            return if (stored >= 0L) stored.toUInt() else fallback
        }

        fun mappedDpadMask(context: Context, direction: String, fallback: UInt): UInt {
            val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("dpad_$direction", -1L)
            return if (stored >= 0L) stored.toUInt() else fallback
        }
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val targets = intArrayOf(
        R.id.touchpadView, R.id.dpadView, R.id.leftAnalogStickView, R.id.rightAnalogStickView,
        R.id.faceButtonsLayout, R.id.crossButtonView, R.id.moonButtonView, R.id.boxButtonView, R.id.pyramidButtonView,
        R.id.l3ButtonView, R.id.r3ButtonView, R.id.psButtonView,
        R.id.l2ButtonView, R.id.l1ButtonView, R.id.shareButtonView, R.id.r2ButtonView,
        R.id.r1ButtonView, R.id.optionsButtonView
    )
    private val defaults = mutableMapOf<Int, ConstraintLayout.LayoutParams>()
    private var enabled = false

    init { applySavedFrames() }

    fun setEnabled(value: Boolean) {
        enabled = value
        targets.forEach { id ->
            val target = root.findViewById<View>(id) ?: return@forEach
            target.setOnTouchListener(if (value) createTouchListener(target) else null)
            target.isSelected = value
        }
        Toast.makeText(context, if (value) "وضع ترتيب الأزرار: اسحب العنصر لتحريكه واضغط عليه لتغيير حجمه أو وظيفته" else "تم حفظ تخطيط أزرار اللعب", Toast.LENGTH_SHORT).show()
    }

    private fun applySavedFrames() {
        targets.forEach { id ->
            val view = root.findViewById<View>(id) ?: return@forEach
            val lp = view.layoutParams as? ConstraintLayout.LayoutParams ?: return@forEach
            defaults.putIfAbsent(id, ConstraintLayout.LayoutParams(lp))
            if (prefs.contains("x_$id") && prefs.contains("w_$id") && prefs.contains("h_$id")) {
                lp.width = prefs.getInt("w_$id", lp.width)
                lp.height = prefs.getInt("h_$id", lp.height)
                clearConstraints(lp)
                lp.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
                lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                lp.leftMargin = prefs.getInt("x_$id", lp.leftMargin).coerceAtLeast(0)
                lp.topMargin = prefs.getInt("y_$id", lp.topMargin).coerceAtLeast(0)
                view.layoutParams = lp
            }
            if (prefs.contains("alpha_$id")) {
                view.alpha = prefs.getInt("alpha_$id", 100).coerceIn(20, 100) / 100f
            }
        }
    }

    private fun createTouchListener(target: View): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var originLeft = 0
        var originTop = 0
        var moved = false
        return View.OnTouchListener { view, event ->
            if (!enabled) return@OnTouchListener false
            val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    originLeft = view.left
                    originTop = view.top
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 8) moved = true
                    if (moved) {
                        makeAbsolute(view, params)
                        val parent = view.parent as? View
                        val maxLeft = ((parent?.width ?: root.width) - view.width).coerceAtLeast(0)
                        val maxTop = ((parent?.height ?: root.height) - view.height).coerceAtLeast(0)
                        params.leftMargin = (originLeft + dx).coerceIn(0, maxLeft)
                        params.topMargin = (originTop + dy).coerceIn(0, maxTop)
                        view.layoutParams = params
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) saveFrame(view, params) else showOptions(view)
                    true
                }
                else -> true
            }
        }
    }

    private fun saveFrame(view: View, params: ConstraintLayout.LayoutParams) {
        val id = view.id
        prefs.edit()
            .putInt("x_$id", params.leftMargin)
            .putInt("y_$id", params.topMargin)
            .putInt("w_$id", params.width)
            .putInt("h_$id", params.height)
            .apply()
    }

    private fun showOptions(view: View) {
        val entries = mutableListOf("تكبير العنصر", "تصغير العنصر", "إعادة موضعه وحجمه الأصلي", "تعديل الشفافية")
        if (MAPPABLE.containsKey(view.id)) entries.add("تعيين وظيفة هذا الزر")
        if (view.id == R.id.dpadView) entries.add("تخصيص اتجاهات D-pad")
        AlertDialog.Builder(context)
            .setTitle("تخصيص عنصر التحكم")
            .setItems(entries.toTypedArray()) { _, which ->
                when (entries[which]) {
                    "تكبير العنصر" -> resize(view, 1.12f)
                    "تصغير العنصر" -> resize(view, 0.88f)
                    "تعديل الشفافية" -> changeOpacity(view)
                    "إعادة موضعه وحجمه الأصلي" -> reset(view)
                    "تعيين وظيفة هذا الزر" -> mapButton(view)
                    "تخصيص اتجاهات D-pad" -> mapDpadDirection()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun changeOpacity(view: View) {
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

    private fun resize(view: View, scale: Float) {
        val params = view.layoutParams as? ConstraintLayout.LayoutParams ?: return
        makeAbsolute(view, params)
        val oldWidth = if (params.width > 0) params.width else view.width
        val oldHeight = if (params.height > 0) params.height else view.height
        params.width = (oldWidth * scale).roundToInt().coerceIn(dp(36), dp(900))
        params.height = (oldHeight * scale).roundToInt().coerceIn(dp(36), dp(900))
        view.layoutParams = params
        saveFrame(view, params)
    }


    private fun makeAbsolute(view: View, params: ConstraintLayout.LayoutParams) {
        if (params.width <= 0) params.width = view.width.coerceAtLeast(dp(60))
        if (params.height <= 0) params.height = view.height.coerceAtLeast(dp(60))
        if (params.leftMargin == 0) params.leftMargin = view.left.coerceAtLeast(0)
        if (params.topMargin == 0) params.topMargin = view.top.coerceAtLeast(0)
        clearConstraints(params)
        params.leftToLeft = ConstraintLayout.LayoutParams.PARENT_ID
        params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
    }

    private fun clearConstraints(params: ConstraintLayout.LayoutParams) {
        val unset = ConstraintLayout.LayoutParams.UNSET
        params.leftToLeft = unset
        params.leftToRight = unset
        params.rightToLeft = unset
        params.rightToRight = unset
        params.topToTop = unset
        params.topToBottom = unset
        params.bottomToTop = unset
        params.bottomToBottom = unset
        params.startToStart = unset
        params.startToEnd = unset
        params.endToStart = unset
        params.endToEnd = unset
        params.baselineToBaseline = unset
    }

    private fun reset(view: View) {
        val original = defaults[view.id] ?: return
        view.layoutParams = ConstraintLayout.LayoutParams(original)
        view.alpha = 1f
        prefs.edit().remove("x_${view.id}").remove("y_${view.id}").remove("w_${view.id}").remove("h_${view.id}").remove("alpha_${view.id}").apply()
    }

    private fun mapButton(view: View) {
        val current = mappedMask(context, view.id, MAPPABLE[view.id] ?: return)
        val selected = MASKS.indexOf(current)
        AlertDialog.Builder(context)
            .setTitle("الوظيفة الجديدة للزر")
            .setSingleChoiceItems(LABELS.toTypedArray(), selected) { dialog, which ->
                prefs.edit().putLong("map_${view.id}", MASKS[which].toLong()).apply()
                dialog.dismiss()
                Toast.makeText(context, "تم حفظ وظيفة الزر. أعد الضغط عليه لتطبيقها.", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }


    private fun mapDpadDirection() {
        val directions = arrayOf("أعلى", "أسفل", "يسار", "يمين")
        val keys = arrayOf("up", "down", "left", "right")
        AlertDialog.Builder(context).setTitle("اختر اتجاه D-pad")
            .setItems(directions) { _, index ->
                AlertDialog.Builder(context).setTitle("الوظيفة الجديدة للاتجاه")
                    .setItems(LABELS.toTypedArray()) { _, chosen ->
                        prefs.edit().putLong("dpad_${keys[index]}", MASKS[chosen].toLong()).apply()
                        Toast.makeText(context, "تم حفظ تعيين الاتجاه", Toast.LENGTH_SHORT).show()
                    }.setNegativeButton("إلغاء", null).show()
            }.setNegativeButton("إغلاق", null).show()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}
