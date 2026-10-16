// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** All categories stay visible; narrow widths wrap instead of scrolling sideways. */
internal class ParameterMenuLayout(context: Context) : ViewGroup(context) {
    private val gap = (8 * resources.displayMetrics.density + .5f).toInt()
    private val positions = mutableListOf<Pair<Int, Int>>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val usable = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        positions.clear()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) { positions += 0 to 0; continue }
            child.measure(MeasureSpec.makeMeasureSpec(usable, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > paddingLeft && x + child.measuredWidth > width - paddingRight) {
                x = paddingLeft; y += rowHeight + gap; rowHeight = 0
            }
            positions += x to y
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec),
            resolveSize(y + rowHeight + paddingBottom, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val (x, y) = positions[i]
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }
}
