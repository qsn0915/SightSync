package com.sightsync.assistant.accessibility

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.getSystemService
import kotlin.math.roundToInt

class OverlayController(
    private val context: Context,
    private val onClick: () -> Unit,
) {
    private val windowManager = context.getSystemService<WindowManager>()
    private var view: View? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context) || windowManager == null) return

        val button = TextView(context).apply {
            applyOverlayTypography()
            applyOverlayState(active = false)
            gravity = Gravity.CENTER
            minWidth = context.dp(78)
            minHeight = context.dp(62)
            elevation = context.dp(10).toFloat()
            setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(8))
            setOnClickListener { onClick() }
            isClickable = true
            isFocusable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val params = WindowManager.LayoutParams(
            context.dp(78),
            context.dp(62),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 24
            y = 0
        }

        windowManager.addView(button, params)
        view = button
    }

    fun setListening(active: Boolean) {
        val button = view as? TextView ?: return
        button.applyOverlayState(active)
    }

    fun hide() {
        val existing = view ?: return
        windowManager?.removeView(existing)
        view = null
    }

    private fun TextView.applyOverlayTypography() {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        includeFontPadding = false
        letterSpacing = 0.03f
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        setLineSpacing(0f, 0.92f)
    }

    private fun TextView.applyOverlayState(active: Boolean) {
        text = if (active) {
            overlayLabel(primary = "听", secondary = "停止")
        } else {
            overlayLabel(primary = "AI", secondary = "待命")
        }
        contentDescription = if (active) {
            "停止 SightSync 连续聆听"
        } else {
            "开启 SightSync 连续聆听"
        }
        tooltipText = contentDescription
        background = context.overlayBackground(active)
    }

    private fun Context.overlayBackground(active: Boolean): RippleDrawable {
        val colors = if (active) {
            intArrayOf(0xFF3B1711.toInt(), 0xFFC24A20.toInt())
        } else {
            intArrayOf(0xFF102C2B.toInt(), 0xFF0B6B57.toInt())
        }
        val strokeColor = if (active) 0xFFFFC48C.toInt() else 0xFFB9F2D6.toInt()
        val shape = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(2), strokeColor)
        }
        return RippleDrawable(
            ColorStateList.valueOf(0x33FFFFFF),
            shape,
            null,
        )
    }

    private fun overlayLabel(primary: String, secondary: String): SpannableString {
        val separator = "\n"
        val label = "$primary$separator$secondary"
        return SpannableString(label).apply {
            setSpan(
                RelativeSizeSpan(1.28f),
                0,
                primary.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                primary.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                RelativeSizeSpan(0.78f),
                primary.length + separator.length,
                label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                StyleSpan(Typeface.NORMAL),
                primary.length + separator.length,
                label.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun Context.dp(value: Int): Int {
        return (value * resources.displayMetrics.density).roundToInt()
    }
}
