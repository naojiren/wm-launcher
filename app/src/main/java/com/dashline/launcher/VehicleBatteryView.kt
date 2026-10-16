// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Path
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/** Battery glyph using vehicle SOC, with no bitmap or background service. */
class VehicleBatteryView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private val batteryPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = RectF()
    private val fill = RectF()
    private val bolt = Path()
    var isCharging: Boolean = false
        set(value) { if (field != value) { field = value; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        val stroke = 2f * resources.displayMetrics.density
        val capWidth = width * 0.055f
        body.set(stroke, stroke, width - capWidth - stroke * 2, height - stroke)
        val radius = height * 0.14f
        batteryPaint.color = Color.rgb(66, 211, 110)
        batteryPaint.style = Paint.Style.STROKE
        batteryPaint.strokeWidth = stroke
        canvas.drawRoundRect(body, radius, radius, batteryPaint)
        batteryPaint.style = Paint.Style.FILL
        canvas.drawRoundRect(RectF(body.right + stroke, height * 0.32f,
            width - stroke, height * 0.68f), stroke, stroke, batteryPaint)
        val percent = text.toString().removeSuffix("%").toIntOrNull()?.takeIf { it in 0..100 }
        if (percent != null && percent > 0) {
            fill.set(body.left + stroke * 2, body.top + stroke * 2,
                body.left + stroke * 2 + (body.width() - stroke * 4) * percent / 100f,
                body.bottom - stroke * 2)
            canvas.save()
            canvas.clipRect(fill)
            canvas.drawRoundRect(RectF(body.left + stroke * 2, fill.top,
                body.right - stroke * 2, fill.bottom), radius / 2, radius / 2, batteryPaint)
            canvas.restore()
        }
        val label = text.toString()
        batteryPaint.typeface = typeface
        batteryPaint.textSize = minOf(textSize, height * 0.49f)
        val textArea = if (isCharging) body.width() * 0.68f else body.width() * 0.84f
        val required = batteryPaint.measureText(label)
        if (required > textArea) batteryPaint.textSize *= textArea / required
        val baseline = body.centerY() - (batteryPaint.fontMetrics.ascent + batteryPaint.fontMetrics.descent) / 2
        val center = body.centerX() + if (isCharging) body.width() * .10f else 0f
        val x = center - batteryPaint.measureText(label) / 2
        batteryPaint.color = Color.rgb(16, 35, 42)
        batteryPaint.style = Paint.Style.STROKE
        batteryPaint.strokeWidth = stroke * 1.4f
        canvas.drawText(label, x, baseline, batteryPaint)
        if (isCharging) {
            // Static vector glyph: no animation timer or extra background work.
            val cx = body.left + body.width() * .14f
            val cy = body.centerY()
            val h = body.height() * .31f
            val w = body.width() * .075f
            bolt.reset(); bolt.moveTo(cx + w * .4f, cy - h)
            bolt.lineTo(cx - w, cy + h * .15f); bolt.lineTo(cx, cy + h * .15f)
            bolt.lineTo(cx - w * .4f, cy + h); bolt.lineTo(cx + w, cy - h * .15f)
            bolt.lineTo(cx, cy - h * .15f); bolt.close()
            batteryPaint.color = Color.rgb(16, 35, 42); batteryPaint.style = Paint.Style.STROKE
            batteryPaint.strokeWidth = stroke * 1.2f; canvas.drawPath(bolt, batteryPaint)
            batteryPaint.color = Color.WHITE; batteryPaint.style = Paint.Style.FILL
            canvas.drawPath(bolt, batteryPaint)
        }
        batteryPaint.color = Color.WHITE
        batteryPaint.style = Paint.Style.FILL
        canvas.drawText(label, x, baseline, batteryPaint)
    }
}
