// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.os.Bundle
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Large touch targets and independently scrolling categories work in both orientations. */
class ParametersActivity : BaseActivity() {
    private lateinit var catalog: WmParameterCatalog
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var tabs: ParameterMenuLayout
    private var selected = "overview"
    @Volatile private var visible = false
    @Volatile private var generation = 0
    private var poll: WmForegroundPoll<List<DetailRow>>? = null
    private var lastRows: List<DetailRow>? = null
    private val values = mutableListOf<TextView>()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + .5f).toInt()
    private fun text(value: String, size: Float, accent: Boolean = false) = TextView(this).apply {
        text = value; textSize = size
        setTextColor(if (accent) accentColor() else ContextCompat.getColor(this@ParametersActivity, R.color.text_primary))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        catalog = WmParameterCatalog(this)
        selected = savedInstanceState?.getString("category")?.takeIf { id -> catalog.categories.any { it.id == id } } ?: "overview"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = layoutInflater.inflate(R.layout.view_page_header, root, false)
        header.findViewById<TextView>(R.id.pageTitle).apply { text = "详细参数"; textSize += 1f }
        listOf(R.id.navBack, R.id.navHome).forEach { id -> header.findViewById<View>(id).layoutParams.apply { width = dp(64); height = dp(64) } }
        header.layoutParams.height = dp(76)
        bindPageNav(header.findViewById(R.id.navBack), header.findViewById(R.id.navHome))
        root.addView(header)
        tabs = ParameterMenuLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        for (category in catalog.categories) tabs.addView(text(category.label, 26f).apply {
            tag = category.id; minHeight = dp(68); gravity = Gravity.CENTER
            setPadding(dp(22), dp(10), dp(22), dp(10)); isFocusable = true
            setOnClickListener { select(category.id) }
        }, android.view.ViewGroup.LayoutParams(-2, -2))
        root.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        status = text("正在读取…", 21f).apply { setPadding(dp(20), dp(8), dp(20), dp(8)); contentDescription = "参数刷新状态" }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        scroll = ScrollView(this).apply { isFillViewport = true }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(4), dp(20), dp(20)) }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(text("仅显示读取结果；不可用不代表数值为零。", 19f).apply { setPadding(dp(20), dp(8), dp(20), dp(12)) })
        setContentView(root)
        paintTabs()
    }
    private fun select(id: String) {
        if (selected == id) return
        selected = id; generation++; poll?.stop(); poll = null
        lastRows = null; values.clear(); body.removeAllViews(); scroll.scrollTo(0, 0)
        paintTabs()
        if (visible) startRead()
    }
    private fun paintTabs() {
        for (i in 0 until tabs.childCount) (tabs.getChildAt(i) as TextView).apply {
            val active = tag == selected
            setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            setTextColor(if (active) accentColor() else ContextCompat.getColor(this@ParametersActivity, R.color.text_primary))
            background = GradientThemes.roundedRect(ContextCompat.getColor(this@ParametersActivity,
                if (active) R.color.media_surface else R.color.surface), dp(8).toFloat())
        }
    }
    private fun startRead() {
        val category = selected
        val token = ++generation
        val reader = WmParametersReader(applicationContext, catalog)
        status.text = "正在读取…"
        poll = WmForegroundPoll("WM-Parameters", 10000, { reader.read(category) { visible && token == generation } }) { rows ->
            if (visible && token == generation && selected == category) render(rows)
        }.also { it.start() }
    }
    private fun render(rows: List<DetailRow>) {
        status.text = "${catalog.categories.first { it.id == selected }.label} · ${rows.count { !it.heading }} 项 · " +
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " 更新"
        if (rows == lastRows) return
        val previous = lastRows
        if (previous != null && previous.map { it.label to it.heading } == rows.map { it.label to it.heading }) {
            rows.forEachIndexed { i, row -> values[i].text = row.value }
        } else {
            body.removeAllViews(); values.clear()
            for (row in rows) {
                val card = LinearLayout(this).apply {
                    orientation = if (resources.configuration.screenWidthDp >= 650 && !row.heading) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(14), dp(20), dp(14))
                    background = GradientThemes.roundedRect(ContextCompat.getColor(this@ParametersActivity, R.color.surface), dp(8).toFloat())
                }
                val label = text(row.label, if (row.heading) 29f else 25f, row.heading)
                card.addView(label, if (card.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
                    else LinearLayout.LayoutParams(-1, -2))
                val value = text(row.value, 29f).apply { setPadding(0, dp(8), 0, 0); setTextIsSelectable(true) }
                values += value
                if (!row.heading) card.addView(value, if (card.orientation == LinearLayout.HORIZONTAL) LinearLayout.LayoutParams(0, -2, 1f)
                    else LinearLayout.LayoutParams(-1, -2))
                body.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
            }
        }
        lastRows = rows
    }
    override fun onResume() { super.onResume(); visible = true; startRead() }
    override fun onPause() { visible = false; generation++; poll?.stop(); poll = null; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("category", selected); super.onSaveInstanceState(outState) }
}
