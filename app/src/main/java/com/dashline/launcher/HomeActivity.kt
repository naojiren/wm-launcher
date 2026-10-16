// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Typeface
import android.widget.SeekBar
import android.widget.Toast
import android.view.KeyEvent
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dashline.launcher.databinding.ActivityHomeBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SYNC-style home dashboard: top status bar (home / clock / wifi), a clock +
 * clock panel on the left, a media + phone panel on the right, and a bottom
 * tab bar.
 */
class HomeActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private var mediaMonitor: MediaMonitor? = null
    private var currentMedia: MediaInfo? = null
    private val mediaArrowKeys = MediaArrowKeys()
    private val factoryMediaKeys = WmMediaKeysMonitor(onKey = ::onFactoryMediaKey)
    private var draggingMedia = false
    private var needsMediaAccess = false

    private lateinit var audioManager: android.media.AudioManager
    private var draggingVolume = false
    private var mediaCompact = false
    private var showSeek = true
    /** Packages currently drawn in the portrait drawer, to skip pointless rebuilds. */
    private var drawerSignature: String? = null
    private var drawerPages: List<List<AppInfo>> = emptyList()
    private var drawerPage = 0
    private var drawerRows = 0
    private var drawerRowHeight = 0
    /** One update prompt per launch, however often the dashboard resumes. */
    private var offeredUpdate = false
    /** Whether this screen is currently counted as a widget host listener. */
    private var attachedWidgets = false
    private var packageReceiverRegistered = false
    private var tirePressureMonitor: WmTirePressureMonitor? = null
    private var lteMonitor: WmForegroundPoll<WmLteStatus>? = null
    private var batteryMonitor: WmForegroundPoll<WmBatteryState>? = null
    private var latestVehicle = VehicleReadings()
    private var latestBattery = WmBatteryState()
    private var detailsDialog: WmDetailsDialog? = null
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            drawerSignature = null
            setupPortraitDrawer()
        }
    }


    // Built from the current locale in onCreate, so they follow the chosen language.
    private lateinit var timeFormat: SimpleDateFormat
    private lateinit var dateFormat: SimpleDateFormat

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            updateWifi()
            updateMediaProgress()
            syncVolume()
            updateChrono()
            handler.postDelayed(this, 1000)
        }
    }

    /**
     * The dashboard ticker runs once a second, which leaves the stopwatch tenths
     * looking frozen. This one runs only while the stopwatch is actually running,
     * and stops itself the moment it isn't.
     */
    private val chronoTicker = object : Runnable {
        override fun run() {
            updateChrono()
            if (prefs.chronoEnabled && prefs.chronoRunning) {
                handler.postDelayed(this, CHRONO_TICK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)
        tirePressureMonitor = WmTirePressureMonitor(renderVehicle = ::renderVehicleReadings) { renderTirePressure(it) }
        val detailsReader = WmDetailsReader(this)
        lteMonitor = WmForegroundPoll("WM-LTE", 30000, detailsReader::lte) { binding.lteIcon.render(it) }
        val batteryReader = WmBatteryAccess(applicationContext)
        batteryMonitor = WmForegroundPoll("WM-Battery", 30000, batteryReader::read) { state ->
            latestBattery = state
            renderVehicleReadings(latestVehicle)
        }
        binding.vehicleStats.batteryValue.setOnClickListener { showVehicleDetails("电池详情", detailsReader::battery) }
        binding.lteIcon.setOnClickListener { showVehicleDetails("连接详情", detailsReader::connections) }
        // All clock styles and the date use the same large, local details entry.
        listOf(binding.bigClock, binding.bigDate, binding.analogClock, binding.clockSlot).forEach { clock ->
            clock.isFocusable = true
            clock.contentDescription = "时间，点击查看详细参数"
            clock.setOnClickListener { startActivity(Intent(this, ParametersActivity::class.java)) }
        }

        val loc = currentLocale()
        timeFormat = SimpleDateFormat("HH:mm", loc)
        dateFormat = SimpleDateFormat(if (loc.language == "zh") "M月d日 EEEE" else "EEEE, d MMMM", loc)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

        setupTabs()
        setupCardsAndBar()
        setupMedia()
        listOf(binding.tripStats.tripAValue, binding.tripStats.tripBValue).forEach { value ->
            value.isClickable = true
            value.isFocusable = true
            value.setOnClickListener {
                AlertDialog.Builder(this).setTitle(if (value === binding.tripStats.tripAValue) "TRIP A" else "TRIP B")
                    .setMessage(R.string.wm_trip_reset_unavailable)
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
        setupVolume()
        setupChrono()
        applyClockStyle()
        applyPanelLayout()
        setupPortraitDrawer()
        // The playing state expands the media card. Re-page the portrait grid
        // after its remaining height changes, including widget changes.
        val repage = android.view.View.OnLayoutChangeListener { view, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if ((top != oldTop || bottom != oldBottom) && isPortraitNow()) {
                view.post { if (!isFinishing && !isDestroyed) setupPortraitDrawer() }
            }
        }
        binding.portraitDrawerGrid.addOnLayoutChangeListener(repage)
        // The whole drawer can move under the media card without changing the
        // grid's own relative bounds, so observe that movement as well.
        binding.portraitDrawer.addOnLayoutChangeListener(repage)
        applyAccent()
    }

    /**
     * Send the user (back) to first-run setup until it's actually finished.
     *
     * This lives in onResume, not onCreate: we're a singleTask HOME activity, so
     * pressing the Home button while setup is open destroys SetupActivity and
     * resumes this one via onNewIntent — onCreate never runs again, which would
     * otherwise let the user skip setup entirely.
     */
    private fun enforceSetup(): Boolean {
        if (prefs.setupDone) return false
        startActivity(
            Intent(this, SetupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        return true
    }

    /**
     * Builds the bottom bar from the user's saved tab order, skipping hidden
     * ones. Rebuilt on resume so changes in Settings show up immediately.
     */
    private fun setupTabs() {
        val bar = binding.tabBar
        bar.removeAllViews()
        val inflater = layoutInflater

        prefs.visibleTabs().forEach { tab ->
            val item = com.dashline.launcher.databinding.ItemTabBinding
                .inflate(inflater, bar, false)
            item.tabIcon.setImageResource(tab.iconRes)
            item.tabLabel.setText(tab.labelRes)
            val active = tab == TabAction.AUDIO
            val color = if (active) accentColor() else ContextCompat.getColor(this, R.color.text_secondary)
            item.tabIcon.tag = TAG_NO_TINT
            item.tabIcon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN)
            item.tabLabel.setTextColor(color)
            if (active) item.root.foreground = ContextCompat.getDrawable(this, R.drawable.wm_tab_selection)
            item.root.setOnClickListener { onTab(tab) }
            bar.addView(
                item.root,
                android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
                )
            )
        }
    }

    private fun onTab(tab: TabAction) = when (tab) {
        TabAction.AUDIO -> launchRole(Prefs.ROLE_MEDIA)
        TabAction.RADIO -> launchRole(Prefs.ROLE_RADIO)
        TabAction.PHONE -> openPhone()
        TabAction.NAV -> openNav()
        TabAction.APPS -> startActivity(Intent(this, AppDrawerActivity::class.java))
        TabAction.SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun setupCardsAndBar() {
        binding.mediaCard.setOnClickListener { openMedia() }
        binding.phoneCard.setOnClickListener { openPhone() }
        // Phone projection is usually a third-party link app (ZLink, EasyConnected…),
        // so these fall through to the picker when nothing known is installed.
        binding.shortcutAndroidAuto.setOnClickListener {
            val entry = WmFactory.entries.first { it.id == "vehicle" }
            if (!AppRepository.launch(this, WmFactory.target(this, entry))) {
                startActivity(Intent(this, FactoryAppsActivity::class.java))
            }
        }
        binding.shortcutCarPlay.setOnClickListener {
            startActivity(Intent(this, FactoryAppsActivity::class.java))
        }
        binding.tirePressure.root.setOnClickListener { binding.shortcutAndroidAuto.performClick() }
        binding.homeButton.setOnClickListener { cleanBackgroundApps() }

        binding.phoneLabel.text = getString(R.string.tab_phone)
    }

    private fun cleanBackgroundApps() {
        val home = java.lang.ref.WeakReference(this)
        val started = WmBackgroundCleaner.start(applicationContext) { message ->
            val activity = home.get() ?: return@start
            if (activity.isFinishing || activity.isDestroyed) return@start
            activity.binding.homeButton.isEnabled = true
            activity.binding.homeButton.alpha = 1f
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
        if (started) {
            binding.homeButton.isEnabled = false
            binding.homeButton.alpha = 0.5f
        }
        Toast.makeText(this, "正在清理第三方后台应用…", Toast.LENGTH_SHORT).show()
    }

    private fun showVehicleDetails(title: String, read: () -> List<DetailRow>) {
        detailsDialog?.dismiss()
        detailsDialog = WmDetailsDialog(this, title, read) { detailsDialog = null }.also { it.show() }
    }

    /**
     * Paints the accent-coloured pieces of the dashboard with the selected
     * gradient's accent: the media card, the play button and the seek bar.
     */
    private fun applyAccent() {
        val accent = accentColor()
        val density = resources.displayMetrics.density
        val cardColor = ContextCompat.getColor(this, R.color.media_surface)
        binding.mediaCard.background = GradientThemes.roundedRect(cardColor, 8f * density)

        binding.btnPlayPause.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(accent)
        }

        // Blue progress over a quiet track, with a larger white control handle.
        @Suppress("DEPRECATION")
        run {
            val seekColor = accent
            // Both bars live on the media card, so they share the same treatment.
            listOf(binding.mediaProgress, binding.volumeSlider).forEach { bar ->
                val progressDrawable = bar.progressDrawable?.mutate()
                if (progressDrawable is android.graphics.drawable.LayerDrawable) {
                    progressDrawable.findDrawableByLayerId(android.R.id.progress)
                        ?.setColorFilter(seekColor, android.graphics.PorterDuff.Mode.SRC_IN)
                    bar.progressDrawable = progressDrawable
                }
                bar.thumbTintList = null
                bar.thumb = ContextCompat.getDrawable(this, R.drawable.wm_seek_thumb)
            }
        }
    }



    // ---- Home panel layout -------------------------------------------------

    /**
     * Applies the user's chosen order, split and lower-card mode to the right
     * column, then rescales the media card's contents to match the space it
     * ended up with. Portrait stacks everything and has no weights, so the
     * resize is skipped there.
     */
    private fun applyPanelLayout() {
        val landscape =
            resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

        val parent = binding.mediaCard.parent as? android.widget.LinearLayout
        if (parent != null) {
            // Card order applies in both orientations. In portrait the cards sit
            // after the clock panel, so reorder relative to their current spot.
            val base = minOf(
                parent.indexOfChild(binding.mediaCard),
                parent.indexOfChild(binding.phoneCard)
            ).coerceAtLeast(0)
            val phoneFirst = prefs.panelOrder == Prefs.PANEL_PHONE_FIRST
            parent.removeView(binding.mediaCard)
            parent.removeView(binding.phoneCard)
            if (phoneFirst) {
                parent.addView(binding.phoneCard, base)
                parent.addView(binding.mediaCard, base + 1)
            } else {
                parent.addView(binding.mediaCard, base)
                parent.addView(binding.phoneCard, base + 1)
            }

            if (landscape) {
                // Weights only mean something in the fixed-height column. A
                // hidden card gives all of it to the other one rather than
                // leaving its share of the column empty.
                val fraction = splitFraction()
                setCardWeight(binding.mediaCard, fraction)
                setCardWeight(binding.phoneCard, 1f - fraction)
                applyMediaDensity(fraction)
            } else {
                // Portrait scrolls, so everything gets its full size.
                showSeek = true
                mediaCompact = false
                binding.mediaVolumeRow.visibility = android.view.View.VISIBLE
            }
        }

        applyCardModes()
    }

    /**
     * The media card's share of the right column. The saved split only applies
     * while both cards are actually there; once one is hidden the other has the
     * column to itself.
     */
    private fun splitFraction(): Float {
        val mediaOff = prefs.cardMode(Prefs.SLOT_MEDIA) == Prefs.CARD_NONE
        val secondOff = prefs.cardMode(Prefs.SLOT_SECOND) == Prefs.CARD_NONE
        return when {
            mediaOff && !secondOff -> 0f
            secondOff && !mediaOff -> 1f
            else -> prefs.mediaFraction
        }
    }

    private fun setCardWeight(card: android.view.View, weight: Float) {
        val lp = card.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return
        lp.height = 0
        lp.weight = weight
        // A weighted card must not also enforce a minimum, or it can't shrink.
        (card as? android.view.ViewGroup)?.minimumHeight = 0
        card.layoutParams = lp
    }

    /**
     * Scales the media card's contents to the height it was given. A small card
     * drops the seek row and shrinks the artwork rather than clipping.
     */
    /**
     * Scales the media card to the height it was given, and — more importantly —
     * removes controls *before* they'd be squashed. A cramped seek bar or volume
     * slider looks broken; showing fewer, correctly sized controls does not.
     */
    private fun applyMediaDensity(mediaFraction: Float) {
        // Nothing to scale when the card has been given over to shortcuts or widgets.
        if (prefs.cardMode(Prefs.SLOT_MEDIA) != Prefs.CARD_MEDIA) return
        val density = resources.displayMetrics.density

        // Drop rows in order of expendability as the card shrinks.
        showSeek = mediaFraction >= 0.56f
        val showVolume = mediaFraction >= 0.46f
        val showControls = mediaFraction >= 0.34f
        val compact = mediaFraction < 0.46f

        binding.mediaVolumeRow.visibility =
            if (showVolume) android.view.View.VISIBLE else android.view.View.GONE
        if (!showControls) binding.mediaControls.visibility = android.view.View.GONE

        val art = ((if (compact) 52f else resources.getDimension(R.dimen.album_art) / density) * density).toInt()
        binding.mediaArt.layoutParams = binding.mediaArt.layoutParams.apply {
            width = art
            height = art
        }
        if (currentMedia?.art == null) {
            val pad = ((if (compact) 13f else resources.getDimension(R.dimen.album_art_padding) / density) * density).toInt()
            binding.mediaArt.setPadding(pad, pad, pad, pad)
        }
        binding.mediaArt.requestLayout()

        binding.mediaTitle.textSize = resources.getDimension(R.dimen.media_title) / resources.displayMetrics.scaledDensity

        val btn = resources.getDimensionPixelSize(R.dimen.media_play_size)
        binding.btnPlayPause.layoutParams = binding.btnPlayPause.layoutParams.apply {
            width = btn
            height = btn
        }
        binding.btnPlayPause.background = GradientThemes.roundedRect(accentColor(), btn / 2f)

        mediaCompact = compact
        updateMediaProgress()
    }

    /**
     * Both cards are configurable and share this code: each shows its default
     * content (the player, or the phone shortcut), a row of app shortcuts, or
     * hosted app widgets.
     */
    private fun applyCardModes() {
        applyCardMode(Prefs.SLOT_MEDIA)
        applyCardMode(Prefs.SLOT_SECOND)
        applyPanelSlot(Prefs.SLOT_CLOCK)
        applySectionVisibility()
    }

    /**
     * Hides the containers that have nothing left in them.
     *
     * Turning a section off is only half the job: the panel that held it, and
     * the column that held those, would otherwise stay behind as empty padding.
     * Each one is dropped when every child it can show is off, so a stripped
     * dashboard closes up instead of leaving holes.
     */
    private fun applySectionVisibility() {
        val projection = prefs.projectionVisible
        binding.projectionRow.visibility =
            if (projection) android.view.View.VISIBLE else android.view.View.GONE

        val clockOff = prefs.cardMode(Prefs.SLOT_CLOCK) == Prefs.CARD_NONE
        binding.clockPanel.visibility =
            if (clockOff && !projection) android.view.View.GONE
            else android.view.View.VISIBLE

        // In landscape the two cards have a column of their own; in portrait
        // they're siblings in the scrolling stack, and hiding that stack would
        // take the clock panel and the drawer with it.
        if (!isLandscapeNow()) return
        val cardsOff = prefs.cardMode(Prefs.SLOT_MEDIA) == Prefs.CARD_NONE &&
            prefs.cardMode(Prefs.SLOT_SECOND) == Prefs.CARD_NONE
        (binding.mediaCard.parent as? android.view.View)?.visibility =
            if (cardsOff) android.view.View.GONE else android.view.View.VISIBLE
    }

    /**
     * The clock section of the left panel, replaceable by
     * widgets — but only upright, where the panel is a stacked card with the
     * drawer below to absorb the difference. In landscape the two of them fill
     * the column, so a widget there would leave a hole; the saved choice is
     * simply ignored rather than reset, so rotating back restores it.
     */
    private fun applyPanelSlot(slot: String) {
        val content = binding.clockSlot
        val widgets = binding.clockWidget

        val mode = prefs.cardMode(slot)
        val off = mode == Prefs.CARD_NONE
        val asWidget = !off && isPortraitNow() && mode == Prefs.CARD_WIDGET
        content.visibility =
            if (off || asWidget) android.view.View.GONE else android.view.View.VISIBLE
        widgets.visibility = if (asWidget) android.view.View.VISIBLE else android.view.View.GONE

        if (asWidget) bindCardWidgets(slot, widgets)
    }

    private fun isPortraitNow(): Boolean = !isLandscapeNow()

    private fun applyCardMode(slot: String) {
        val media = slot == Prefs.SLOT_MEDIA
        val mode = prefs.cardMode(slot)
        val default = if (media) binding.mediaDefault else binding.phoneDefault
        val shortcuts = if (media) binding.mediaShortcuts else binding.panelShortcuts
        val widgets = if (media) binding.mediaWidget else binding.panelWidget
        val card = if (media) binding.mediaCard else binding.phoneCard

        val isShortcuts = mode == Prefs.CARD_SHORTCUTS
        val isWidget = mode == Prefs.CARD_WIDGET
        val isOff = mode == Prefs.CARD_NONE
        val isDefault = !isShortcuts && !isWidget && !isOff

        card.visibility = if (isOff) android.view.View.GONE else android.view.View.VISIBLE

        default.visibility = if (isDefault) android.view.View.VISIBLE else android.view.View.GONE
        shortcuts.visibility = if (isShortcuts) android.view.View.VISIBLE else android.view.View.GONE
        widgets.visibility = if (isWidget) android.view.View.VISIBLE else android.view.View.GONE

        // The card only acts as a shortcut while it's showing its default.
        card.setOnClickListener(
            if (!isDefault) null
            else android.view.View.OnClickListener { if (media) openMedia() else openPhone() }
        )
        card.isClickable = isDefault

        when {
            isShortcuts -> bindCardShortcuts(slot, shortcuts)
            isWidget -> bindCardWidgets(slot, widgets)
        }
    }

    /**
     * Renders a card's widgets side by side. Anything missing — never picked,
     * uninstalled, or permission revoked — is dropped rather than left as an
     * empty hole, and a card with nothing left falls back to its default.
     */
    private fun bindCardWidgets(slot: String, container: android.widget.LinearLayout) {
        val ids = prefs.cardWidgets(slot)
        if (ids.isEmpty()) {
            // Nothing left on the card — that only happens when the user removed
            // the last widget themselves, so fall back to the card's own content.
            prefs.setCardMode(slot, defaultModeFor(slot))
            applyCardMode(slot)
            return
        }

        // Rebuilding on every resume throws away live widget views and starts
        // them back at their placeholder, so only do it when the set changed.
        val signature = ids.joinToString(",")
        if (container.tag == signature && container.childCount == ids.size) return
        container.tag = signature
        container.removeAllViews()

        // The clock panel wraps in both orientations; the cards only in portrait.
        val panelSlot = slot == Prefs.SLOT_CLOCK
        WidgetHost.sizeContainer(container, fill = isLandscapeNow() && !panelSlot)

        var dropped = false
        ids.forEach { id ->
            val view = WidgetHost.createView(this, id)
            when {
                view != null -> {
                    container.addView(view, widgetCell())
                    WidgetHost.sizeOnLayout(this, view, id)
                }
                // Only forget a widget the framework itself has let go of. A
                // provider merely being updated returns null for a few seconds,
                // and deleting on that meant a background app update could
                // silently empty a card.
                WidgetHost.isGoneForGood(this, id) -> {
                    prefs.removeCardWidget(slot, id)
                    dropped = true
                }
                else -> container.addView(unavailableWidget(container), widgetCell())
            }
        }
        if (dropped) {
            // The set changed underneath us; rebuild against what's left.
            container.tag = null
            applyCardMode(slot)
        }
    }

    private fun widgetCell() = android.widget.LinearLayout.LayoutParams(
        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
    )

    /** Stand-in for a widget whose provider isn't answering at the moment. */
    private fun unavailableWidget(parent: android.view.ViewGroup): android.view.View =
        layoutInflater.inflate(R.layout.view_widget_unavailable, parent, false)

    private fun defaultModeFor(slot: String) = prefs.defaultCardMode(slot)

    // ---- portrait app drawer -----------------------------------------------

    /**
     * Portrait stacks the cards and still leaves a large empty area underneath,
     * so the app drawer lives there rather than behind a tab press. Landscape
     * has no such space, and keeps the drawer on the Apps tab only.
     *
     * The drawer never scrolls: it takes whatever height the cards leave, works
     * out how many rows fit, and pages the rest. Reaching for a scrollbar while
     * driving is worse than a swipe, and a half-visible row of icons cut off by
     * the tab bar looks broken.
     */
    private fun setupPortraitDrawer() {
        if (isLandscapeNow()) {
            binding.portraitDrawer.visibility = android.view.View.GONE
            return
        }
        binding.portraitDrawer.visibility = android.view.View.VISIBLE
        binding.portraitDrawerHeader.setOnClickListener {
            startActivity(Intent(this, AppDrawerActivity::class.java))
        }
        attachDrawerSwipe()

        val apps = drawerApps()
        // Rebuilding every tile on resume is wasted work on a slow head unit, so
        // only do it when the app list or the space available actually changed.
        val signature = apps.joinToString(",") { it.packageName }
        val grid = binding.portraitDrawerGrid
        val previousRowHeight = drawerRowHeight
        val rows = rowsThatFit(grid)
        if (signature == drawerSignature && rows == drawerRows && previousRowHeight == drawerRowHeight && grid.childCount > 0) return
        drawerSignature = signature
        drawerRows = rows

        drawerPages = apps.chunked(rows * DRAWER_COLUMNS)
        drawerPage = drawerPage.coerceIn(0, maxOf(0, drawerPages.lastIndex))
        renderDrawerPage()
    }

    /**
     * How many rows of icons fit in the space the cards left over. Measured
     * rather than assumed, since head-unit screens vary wildly — and the grid
     * has no height at all on the very first pass, so fall back to one page.
     */
    private fun rowsThatFit(grid: android.view.View): Int {
        val width = if (grid.width > 0) grid.width else
            (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt()
        // Measure the real two-line tile, including system font scaling. A
        // compact portrait screen must be able to scroll to two complete rows.
        val cell = AppGrid.homeTileHeight(this, width)
        drawerRowHeight = cell
        val density = resources.displayMetrics.density
        val header = maxOf(binding.portraitDrawerHeader.height, (48 * density).toInt())
        val minimum = cell * 2 + header + ((DRAWER_DOT_TOUCH_DP + 24) * density).toInt()
        val drawerParams = binding.portraitDrawer.layoutParams as android.widget.LinearLayout.LayoutParams
        if (drawerParams.height != minimum) {
            drawerParams.height = minimum
            binding.portraitDrawer.layoutParams = drawerParams
        }
        if (grid.height <= 0 || binding.tabBar.height <= 0) {
            // Nothing measured yet; re-run once layout has happened.
            grid.post { setupPortraitDrawer() }
            return 2
        }
        // A ScrollView can measure content taller than its visible viewport.
        // Its child's height alone therefore includes rows behind the bottom
        // bar. Reserve the pager and padding inside the visible screen bounds.
        val gridPosition = IntArray(2)
        val barPosition = IntArray(2)
        grid.getLocationInWindow(gridPosition)
        binding.tabBar.getLocationInWindow(barPosition)
        val pagerAndPadding = ((DRAWER_DOT_TOUCH_DP + 18) * resources.displayMetrics.density).toInt()
        val visible = barPosition[1] - gridPosition[1] - pagerAndPadding
        val available = minOf(grid.height, visible.coerceAtLeast(0))
        drawerRowHeight = AppGrid.homeTileHeight(this, width, available)
        return (available / drawerRowHeight).coerceIn(2, DRAWER_MAX_ROWS)
    }

    private fun renderDrawerPage() {
        val grid = binding.portraitDrawerGrid
        grid.removeAllViews()
        val page = drawerPages.getOrNull(drawerPage) ?: emptyList()
        val rowHeight = drawerRowHeight.takeIf { it > 0 } ?: AppGrid.homeTileHeight(this,
            grid.width.takeIf { it > 0 }
                ?: (resources.configuration.screenWidthDp * resources.displayMetrics.density).toInt())

        page.chunked(DRAWER_COLUMNS).forEach { rowApps ->
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                isBaselineAligned = false
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                )
            }
            rowApps.forEach { app ->
                val item = com.dashline.launcher.databinding.ItemAppBinding
                    .inflate(layoutInflater, row, false)
                item.appIcon.setImageDrawable(app.icon)
                item.appLabel.text = app.label
                item.root.setOnClickListener { AppRepository.launch(this, app.packageName) }
                row.addView(item.root, cellParams())
            }
            // Keep the last row aligned to the same three columns.
            repeat(DRAWER_COLUMNS - rowApps.size) {
                row.addView(android.view.View(this), cellParams())
            }
            grid.addView(row)
        }
        // Keep every page the same shape: a short last page shouldn't spread its
        // one row down the middle of the card.
        repeat(drawerRows - page.chunked(DRAWER_COLUMNS).size) {
            grid.addView(
                android.view.View(this),
                android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, rowHeight
                )
            )
        }
        renderDrawerDots()
        tintIcons(grid)
    }

    private fun renderDrawerDots() {
        val dots = binding.portraitDrawerDots
        dots.removeAllViews()
        if (drawerPages.size < 2) return

        val density = resources.displayMetrics.density
        val size = (DRAWER_DOT_DP * density).toInt()
        val touch = (DRAWER_DOT_TOUCH_DP * density).toInt()
        val accent = accentColor()

        drawerPages.indices.forEach { index ->
            val dot = android.view.View(this).apply {
                background = GradientThemes.roundedRect(
                    if (index == drawerPage) accent
                    else GradientThemes.withAlpha(accent, 0x55),
                    size / 2f
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).also {
                    it.setMargins((touch - size) / 2, 0, (touch - size) / 2, 0)
                }
                // The dot itself is small, so give it a driver-sized touch area.
                minimumWidth = touch
                minimumHeight = touch
                setOnClickListener { showDrawerPage(index) }
                contentDescription =
                    getString(R.string.drawer_page, index + 1, drawerPages.size)
            }
            dots.addView(dot)
        }
    }

    private fun showDrawerPage(index: Int) {
        if (index !in drawerPages.indices || index == drawerPage) return
        drawerPage = index
        renderDrawerPage()
    }

    /** Swipe left/right anywhere on the grid to change page. */
    private fun attachDrawerSwipe() {
        binding.portraitDrawerGrid.onSwipe = { direction ->
            showDrawerPage(drawerPage + direction)
        }
    }

    private fun cellParams() = android.widget.LinearLayout.LayoutParams(
        0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f
    )

    /** Third-party apps only: most-used first, then the rest alphabetically. */
    private fun drawerApps(): List<AppInfo> {
        val all = AppRepository.loadLaunchableApps(this, prefs.hiddenApps)
            .filter { AppCategory.THIRD_PARTY.includes(it) }
        val top = Usage.topApps(this, DRAWER_COLUMNS * DRAWER_MAX_ROWS)
        val byPackage = all.associateBy { it.packageName }
        val frequent = top.mapNotNull { byPackage[it] }
        val rest = all.filter { it.packageName !in top }
        return frequent + rest
    }

    private fun isLandscapeNow(): Boolean =
        resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE

    private fun bindCardShortcuts(slot: String, row: android.widget.LinearLayout) {
        val inflater = layoutInflater
        row.showDividers = android.widget.LinearLayout.SHOW_DIVIDER_MIDDLE
        row.dividerDrawable = ContextCompat.getDrawable(this, R.drawable.wm_vertical_divider)
        row.dividerPadding = (12 * resources.displayMetrics.density).toInt()

        AppGrid.shortcuts(row, Prefs.PANEL_SHORTCUT_COUNT) { index, tileRow ->
            val pkg = prefs.cardShortcut(slot, index)
            val item = com.dashline.launcher.databinding.ItemAppBinding
                .inflate(inflater, tileRow, false)
            item.root.minimumHeight = 0
            item.appLabel.minLines = 1
            item.appLabel.setTypeface(null, Typeface.BOLD)

            if (pkg != null && AppRepository.isInstalled(this, pkg)) {
                val factoryIcon = WmFactory.iconRes(pkg)
                if (factoryIcon != null) {
                    item.appIcon.setImageResource(factoryIcon)
                    item.appIcon.setColorFilter(accentColor(), android.graphics.PorterDuff.Mode.SRC_IN)
                } else {
                    item.appIcon.setImageDrawable(AppRepository.iconFor(this, pkg))
                    item.appIcon.clearColorFilter()
                }
                item.appLabel.text = AppRepository.labelFor(this, pkg)
                item.root.setOnClickListener { AppRepository.launch(this, pkg) }
                item.root.setOnLongClickListener { pickCardShortcut(slot, index); true }
            } else {
                item.appIcon.setImageResource(R.drawable.ic_add)
                item.appIcon.setColorFilter(
                    accentColor(), android.graphics.PorterDuff.Mode.SRC_IN
                )
                item.appLabel.text = ""
                item.root.setOnClickListener { pickCardShortcut(slot, index) }
                item.root.setOnLongClickListener { false }
            }
            item.root
        }
    }

    /** Request codes are laid out per slot so the result knows where to land. */
    private fun pickCardShortcut(slot: String, index: Int) {
        val base = if (slot == Prefs.SLOT_MEDIA) REQ_MEDIA_PANEL_BASE else REQ_PANEL_BASE
        AppPickerActivity.start(this, Prefs.ROLE_FAVORITE, base + index)
    }

    // ---- Clock style + stopwatch -------------------------------------------

    /** Swaps between the digital, analog and minimal faces. */
    private fun applyClockStyle() {
        val style = prefs.clockStyle
        val analog = style == Prefs.CLOCK_ANALOG
        val minimal = style == Prefs.CLOCK_MINIMAL

        binding.analogClock.visibility =
            if (analog) android.view.View.VISIBLE else android.view.View.GONE
        binding.bigClock.visibility =
            if (analog) android.view.View.GONE else android.view.View.VISIBLE
        // Minimal is time only — the date is what makes it "not minimal".
        binding.bigDate.visibility =
            if (minimal) android.view.View.GONE else android.view.View.VISIBLE

        binding.bigClock.setTypeface(null, if (minimal) Typeface.NORMAL else Typeface.BOLD)

        if (analog) {
            binding.analogClock.setColors(
                hands = ContextCompat.getColor(this, R.color.text_primary),
                ticks = ContextCompat.getColor(this, R.color.text_secondary),
                accentColor = accentColor()
            )
        }
        updateChronoVisibility()
    }

    private fun setupChrono() {
        binding.chronoToggle.setOnClickListener {
            if (prefs.chronoRunning) {
                // Bank the elapsed time and stop.
                prefs.chronoAccumulated += SystemClock.elapsedRealtime() - prefs.chronoStartedAt
                prefs.chronoStartedAt = 0L
            } else {
                prefs.chronoStartedAt = SystemClock.elapsedRealtime()
            }
            updateChrono()
            startChronoTicker()
        }
        binding.chronoReset.setOnClickListener {
            prefs.chronoStartedAt = 0L
            prefs.chronoAccumulated = 0L
            updateChrono()
        }
    }

    private fun updateChronoVisibility() {
        binding.chronoRow.visibility =
            if (prefs.chronoEnabled) android.view.View.VISIBLE else android.view.View.GONE
        updateChrono()
        startChronoTicker()
    }

    /** Runs the fast ticker only while the stopwatch is counting. */
    private fun startChronoTicker() {
        handler.removeCallbacks(chronoTicker)
        if (prefs.chronoEnabled && prefs.chronoRunning) handler.post(chronoTicker)
    }

    private fun updateChrono() {
        if (!prefs.chronoEnabled) return
        val running = prefs.chronoRunning
        val elapsed = prefs.chronoAccumulated +
            if (running) SystemClock.elapsedRealtime() - prefs.chronoStartedAt else 0L
        binding.chronoText.text = formatChrono(elapsed)
        binding.chronoToggle.setImageResource(
            if (running) R.drawable.ic_pause else R.drawable.ic_play
        )
    }

    /** m:ss.hh with hundredths, or h:mm:ss once past an hour. */
    private fun formatChrono(ms: Long): String {
        val hundredths = (ms / 10) % 100
        val secs = ms / 1000
        val h = secs / 3600
        val m = (secs % 3600) / 60
        val sec = secs % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d.%02d", m, sec, hundredths)
    }

    // ---- Volume ------------------------------------------------------------

    private fun setupVolume() {
        val stream = android.media.AudioManager.STREAM_MUSIC
        binding.volumeSlider.max = audioManager.getStreamMaxVolume(stream)
        syncVolume()

        binding.volumeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                runCatching { audioManager.setStreamVolume(stream, progress, 0) }
                updateVolumeIcon(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar) { draggingVolume = true }
            override fun onStopTrackingTouch(sb: SeekBar) { draggingVolume = false }
        })
        binding.volumeIcon.setOnClickListener { toggleMute() }
    }

    /** Keeps the slider in step with the unit's own volume knob / hardware keys. */
    private fun syncVolume() {
        if (draggingVolume) return
        val vol = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        binding.volumeSlider.progress = vol
        updateVolumeIcon(vol)
    }

    private fun updateVolumeIcon(vol: Int) {
        binding.volumeIcon.setImageResource(
            if (vol == 0) R.drawable.ic_volume_off else R.drawable.ic_volume
        )
    }

    private fun toggleMute() {
        val stream = android.media.AudioManager.STREAM_MUSIC
        val vol = audioManager.getStreamVolume(stream)
        val target = if (vol > 0) 0 else audioManager.getStreamMaxVolume(stream) / 2
        runCatching { audioManager.setStreamVolume(stream, target, 0) }
        binding.volumeSlider.progress = target
        updateVolumeIcon(target)
    }

    // ---- Media widget ------------------------------------------------------

    private fun controlMedia(action: () -> Boolean?) {
        if (action() != true) Toast.makeText(this, R.string.wm_media_control_failed, Toast.LENGTH_LONG).show()
        binding.root.postDelayed({ updateMediaProgress() }, 250)
    }

    private fun skipMediaTrack(arrow: Int) = controlMedia {
        when (arrow) {
            KeyEvent.KEYCODE_DPAD_LEFT -> mediaMonitor?.previous()
            KeyEvent.KEYCODE_DPAD_RIGHT -> mediaMonitor?.next()
            else -> false
        }
    }

    private fun onFactoryMediaKey(event: KeyEvent) {
        if (!hasWindowFocus() || !::binding.isInitialized || !::prefs.isInitialized ||
            !prefs.setupDone || !binding.mediaHeader.isShown) return
        val arrow = WmMediaKeyPolicy.arrow(event, currentMedia?.packageName) ?: return
        android.util.Log.i("WM-MediaKeys", "Factory key=${event.keyCode} -> current source=${currentMedia?.packageName}")
        skipMediaTrack(arrow)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val focus = currentFocus
        val enabled = hasWindowFocus() && ::binding.isInitialized && ::prefs.isInitialized && prefs.setupDone &&
            binding.mediaHeader.isShown && focus !is SeekBar && focus !is android.widget.EditText &&
            (currentMedia != null || (::audioManager.isInitialized && audioManager.isMusicActive))
        if (mediaArrowKeys.dispatch(event, enabled, ::skipMediaTrack)) return true
        return super.dispatchKeyEvent(event)
    }

    private fun setupMedia() {
        // Give hardware keyboards a music focus target before the default slider.
        for (view in listOf(binding.mediaHeader, binding.btnPrev, binding.btnPlayPause, binding.btnNext)) {
            view.isFocusable = true
        }
        // Android 6 otherwise consumes the first arrow to leave touch mode.
        // Use the non-clickable title so the header still opens with one tap.
        binding.mediaTitle.isFocusableInTouchMode = true
        binding.btnPlayPause.setOnClickListener { controlMedia { mediaMonitor?.playPause() } }
        binding.btnNext.setOnClickListener { skipMediaTrack(KeyEvent.KEYCODE_DPAD_RIGHT) }
        binding.btnPrev.setOnClickListener { skipMediaTrack(KeyEvent.KEYCODE_DPAD_LEFT) }
        binding.mediaHeader.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutMediaControls() }

        binding.mediaProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.mediaElapsed.text = formatDuration(progress.toLong())
            }
            override fun onStartTrackingTouch(sb: SeekBar) { draggingMedia = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                draggingMedia = false
                mediaMonitor?.seekTo(sb.progress.toLong())
            }
        })

        // MediaSessionManager is API 21+. On older units, stay in launch-only mode.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mediaMonitor = MediaMonitor(this) { info -> renderMedia(info) }
        }
        renderMedia(null)
    }

    /** Large portrait screens place controls beside the title, preserving app rows. */
    private fun layoutMediaControls() {
        val controls = binding.mediaControls
        val inline = binding.mediaHeader.width >= (740 * resources.displayMetrics.density).toInt()
        val parent = if (inline) binding.mediaHeader else binding.mediaDefault
        if (controls.parent === parent) return
        (controls.parent as android.view.ViewGroup).removeView(controls)
        val params = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        params.gravity = if (inline) android.view.Gravity.CENTER_VERTICAL else android.view.Gravity.CENTER_HORIZONTAL
        params.topMargin = if (inline) 0 else (8 * resources.displayMetrics.density).toInt()
        params.marginStart = if (inline) (8 * resources.displayMetrics.density).toInt() else 0
        if (inline) parent.addView(controls, params)
        else parent.addView(controls, parent.indexOfChild(binding.mediaVolumeRow), params)
    }

    /** Advances the seek bar + time labels; called each tick while media is active. */
    private fun updateMediaProgress() {
        val monitor = mediaMonitor ?: return
        if (currentMedia == null) {
            binding.btnPlayPause.setImageResource(if (audioManager.isMusicActive) R.drawable.ic_pause else R.drawable.ic_play)
            return
        }
        if (currentMedia == null || draggingMedia) return
        val duration = monitor.durationMs()
        if (duration <= 0) {
            // Live streams / radio report no duration — hide the seek row entirely.
            binding.mediaSeekRow.visibility = android.view.View.GONE
            return
        }
        if (!showSeek) {
            // Not enough height — a squashed seek bar reads as broken.
            binding.mediaSeekRow.visibility = android.view.View.GONE
            return
        }
        val position = monitor.positionMs().coerceAtMost(duration)
        binding.mediaSeekRow.visibility = android.view.View.VISIBLE
        binding.mediaProgress.max = duration.toInt()
        binding.mediaProgress.progress = position.toInt()
        binding.mediaElapsed.text = formatDuration(position)
        binding.mediaTotal.text = formatDuration(duration)
    }

    /** m:ss, or h:mm:ss for anything an hour or longer. */
    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun renderMedia(info: MediaInfo?) {
        currentMedia = info
        if (info == null) {
            showPlaceholderArt()
            binding.mediaTitle.isSelected = false
            binding.mediaControls.visibility = android.view.View.VISIBLE
            binding.mediaSeekRow.visibility = android.view.View.GONE
            binding.mediaArtist.visibility = android.view.View.VISIBLE
            binding.btnPlayPause.setImageResource(if (audioManager.isMusicActive) R.drawable.ic_pause else R.drawable.ic_play)

            // On 5.0+, reading what's playing needs Notification access. If it isn't
            // granted, the widget can never see media — guide the user to enable it.
            needsMediaAccess = mediaMonitor != null && !NotificationAccess.isGranted(this)
            if (needsMediaAccess) {
                binding.mediaTitle.text = getString(R.string.media_nothing_playing)
                binding.mediaArtist.text = getString(R.string.media_enable_access)
                return
            }
            val mediaLabel = AppRepository.labelFor(this, prefs.mediaApp)
            binding.mediaTitle.text = getString(R.string.media_nothing_playing)
            binding.mediaArtist.text = mediaLabel?.let {
                "${getString(R.string.media_tap_to_open)} $it"
            } ?: getString(R.string.media_tap_to_open)
            return
        }
        needsMediaAccess = false
        if (info.art != null) showAlbumArt(info.art) else showPlaceholderArt()

        binding.mediaTitle.text = info.title.ifEmpty { getString(R.string.now_playing) }
        binding.mediaTitle.isSelected = true  // starts the marquee for long titles
        binding.mediaArtist.text = info.artist
        binding.mediaArtist.visibility =
            if (info.artist.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnPlayPause.setImageResource(
            if (info.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        // Respect whatever applyMediaDensity decided there was room for.
        if (prefs.mediaFraction >= 0.34f || !isLandscapeNow()) {
            binding.mediaControls.visibility = android.view.View.VISIBLE
        }
        updateMediaProgress()
    }

    /** Square-crops the album art and rounds its corners to match the card. */
    private fun showAlbumArt(bitmap: android.graphics.Bitmap) {
        val square = try {
            val size = minOf(bitmap.width, bitmap.height)
            if (bitmap.width == bitmap.height) bitmap
            else android.graphics.Bitmap.createBitmap(
                bitmap, (bitmap.width - size) / 2, (bitmap.height - size) / 2, size, size
            )
        } catch (e: Exception) {
            bitmap
        }
        val rounded = RoundedBitmapDrawableFactory.create(resources, square)
        rounded.cornerRadius = 8f * resources.displayMetrics.density
        binding.mediaArt.setPadding(0, 0, 0, 0)
        binding.mediaArt.clearColorFilter()
        binding.mediaArt.setImageDrawable(rounded)
    }

    /** No artwork: show the inset music glyph on the rounded tile. */
    private fun showPlaceholderArt() {
        val pad = (12f * resources.displayMetrics.density).toInt()
        binding.mediaArt.setPadding(pad, pad, pad, pad)
        binding.mediaArt.setImageResource(R.drawable.ic_audio)
        // The tile opts out of the global icon tinting so real artwork keeps its
        // own colours — which leaves this stand-in glyph to tint itself.
        binding.mediaArt.setColorFilter(accentColor(), android.graphics.PorterDuff.Mode.SRC_IN)
    }

    /** Tapping the media card: enable access, else open the playing / chosen app. */
    private fun openMedia() {
        if (needsMediaAccess) {
            NotificationAccess.openSettings(this)
            return
        }
        val playingPkg = currentMedia?.packageName
        if (playingPkg != null && AppRepository.launch(this, playingPkg)) return
        if (!AppRepository.launchFirstAvailable(this, prefs.candidatesFor(Prefs.ROLE_MEDIA))) {
            AppPickerActivity.start(this, Prefs.ROLE_MEDIA, REQ_PICK_GENERIC)
        }
    }

    // ---- Tab actions -------------------------------------------------------

    private fun openPhone() {
        val phone = prefs.phoneApp
        if (phone == null || !AppRepository.isInstalled(this, phone)) {
            // Not chosen yet (or uninstalled): let the user pick one, then launch.
            AppPickerActivity.start(this, Prefs.ROLE_PHONE, REQ_PICK_PHONE)
            return
        }
        if (!AppRepository.launch(this, phone)) toastNotFound()
    }

    private fun openNav() = launchRole(Prefs.ROLE_NAV)

    private fun launchRole(role: String) {
        if (!AppRepository.launchFirstAvailable(this, prefs.candidatesFor(role))) {
            // Nothing installed for this role — help the user choose.
            AppPickerActivity.start(this, role, REQ_PICK_GENERIC)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val pkg = data.getStringExtra(AppPickerActivity.EXTRA_PACKAGE) ?: return
        when {
            requestCode == REQ_PICK_PHONE ->
                AppRepository.launch(this, pkg)  // pref already saved by the picker
            requestCode >= REQ_MEDIA_PANEL_BASE &&
                requestCode < REQ_MEDIA_PANEL_BASE + Prefs.PANEL_SHORTCUT_COUNT -> {
                prefs.setCardShortcut(
                    Prefs.SLOT_MEDIA, requestCode - REQ_MEDIA_PANEL_BASE, pkg
                )
                bindCardShortcuts(Prefs.SLOT_MEDIA, binding.mediaShortcuts)
            }
            requestCode >= REQ_PANEL_BASE &&
                requestCode < REQ_PANEL_BASE + Prefs.PANEL_SHORTCUT_COUNT -> {
                prefs.setCardShortcut(
                    Prefs.SLOT_SECOND, requestCode - REQ_PANEL_BASE, pkg
                )
                bindCardShortcuts(Prefs.SLOT_SECOND, binding.panelShortcuts)
            }
        }
    }

    private fun toastNotFound() =
        Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT).show()

    // ---- Clock -------------------------------------------------------------

    private fun updateClock() {
        val now = Date()
        binding.bigClock.text = timeFormat.format(now)
        binding.bigDate.text = dateFormat.format(now)
        // The analog face has no timer of its own; it repaints from here.
        if (binding.analogClock.visibility == android.view.View.VISIBLE) {
            binding.analogClock.invalidate()
        }
    }

    private fun renderVehicleReadings(values: VehicleReadings) {
        latestVehicle = values
        val current = values.copy(batteryTenths = latestBattery.tenths)
        binding.vehicleStats.batteryValue.text = current.batteryText
        binding.vehicleStats.batteryValue.isCharging = latestBattery.charging == true
        binding.vehicleStats.batteryValue.contentDescription = "电量 ${current.batteryText}，" +
            if (latestBattery.charging == true) "充电中" else if (latestBattery.plugged == true) "已连接充电枪" else "未充电或状态不可用"
        binding.vehicleStats.rangeValue.setRanges(current.rangeText, current.actualEstimatedRangeText)
        binding.tripStats.tripAValue.text = values.tripAText
        binding.tripStats.tripBValue.text = values.tripBText
        binding.tripStats.totalValue.text = values.totalText
    }

    private fun renderTirePressure(readings: List<TirePressureReading>?) {
        val panel = binding.tirePressure
        panel.pressureTitle.setText(R.string.wm_pressure_title_kpa)
        val labels = listOf(panel.frontLeft, panel.frontRight, panel.rearLeft, panel.rearRight)
        labels.forEachIndexed { index, label ->
            val reading = readings?.getOrNull(index)
            label.text = reading?.kpa?.toString() ?: getString(R.string.wm_pressure_unknown)
            label.setTextColor(if (reading?.hasWarning == true) android.graphics.Color.rgb(255, 178, 79)
                else ContextCompat.getColor(this, R.color.text_primary))
        }
        val status = when {
            readings == null -> R.string.wm_pressure_unavailable
            readings.any { it.hasWarning } -> R.string.wm_pressure_warning
            readings.any { it.kpa == null } -> R.string.wm_pressure_partial
            else -> null
        }
        panel.pressureStatus.visibility = if (status == null) android.view.View.GONE else android.view.View.VISIBLE
        if (status != null) panel.pressureStatus.setText(status)
    }

    private fun currentLocale(): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales.get(0)
        } else {
            @Suppress("DEPRECATION") resources.configuration.locale
        }

    @Suppress("DEPRECATION")
    private fun updateWifi() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val onWifi = if (cm == null) {
            false
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork
            val caps = net?.let { cm.getNetworkCapabilities(it) }
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } else {
            // KitKat / Lollipop path.
            cm.activeNetworkInfo?.type == ConnectivityManager.TYPE_WIFI
        }
        binding.wifiIcon.visibility = if (onWifi) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        WmHomeIntegration.ensureOnStart(this)
        // Setup isn't finished — bounce straight back to it.
        if (enforceSetup()) return
        if (!packageReceiverRegistered) {
            val filter = android.content.IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            registerReceiver(packageReceiver, filter)
            packageReceiverRegistered = true
        }
        // Re-apply in case "auto" theme crossed the day/night boundary while away.
        ThemeManager.apply(prefs)
        // This activity is singleTask, so onCreate won't re-run after the user
        // changes the colour scheme — repaint the accent pieces here.
        applyAccent()
        applyKeepScreenOn()
        handler.post(ticker)
        mediaMonitor?.start()
        factoryMediaKeys.start()
        // Widgets only receive updates while the host is listening, and there's
        // no point listening for a dashboard that shows none.
        if (prefs.anyCardUsesWidgets()) {
            WidgetHost.attach(this)
            attachedWidgets = true
        }
        // Reclaim ids left behind by a bind that never finished.
        WidgetHost.sweepOrphans(this, prefs.widgetIdsInUse())
        // Settings changes land here: this is a singleTask HOME activity, so
        // onCreate does not run again when the user comes back from Settings.
        setupTabs()
        applyClockStyle()
        applyPanelLayout()
        setupPortraitDrawer()
        setupVolume()
        tirePressureMonitor?.start()
        batteryMonitor?.start()
        WmBluetoothAudio.refresh(this)
        lteMonitor?.start()
        // Tabs and drawer tiles were just rebuilt, so they missed the tinting
        // BaseActivity did on the way in.
        tintIcons()
        maybeOfferUpdate()
    }

    /**
     * Sideloaded builds have no store to update them, so they look for a newer
     * release themselves — at most once a day, in the background, and never at
     * the cost of a slower start. Shown once per launch so a decline isn't
     * re-asked every time the dashboard comes back into view.
     */
    private fun maybeOfferUpdate() {
        // Coming back from the install-permission screen: carry on where we left off.
        UpdatePrompt.resumeIfReady(this)
        if (offeredUpdate || !prefs.setupDone) return
        UpdateChecker.check(this) { update ->
            if (!isFinishing && !offeredUpdate) {
                offeredUpdate = true
                UpdatePrompt.show(this, update)
            }
        }
    }

    private fun applyKeepScreenOn() {
        if (prefs.keepScreenOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onPause() {
        super.onPause()
        mediaArrowKeys.reset()
        factoryMediaKeys.stop()
        tirePressureMonitor?.stop()
        batteryMonitor?.stop()
        lteMonitor?.stop()
        detailsDialog?.dismiss()
        if (packageReceiverRegistered) {
            unregisterReceiver(packageReceiver)
            packageReceiverRegistered = false
        }
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(chronoTicker)
        if (attachedWidgets) {
            WidgetHost.detach()
            attachedWidgets = false
        }
        mediaMonitor?.stop()
    }

    // As the HOME app, Back should stay on the dashboard rather than exit.
    override fun onBackPressed() {
        // no-op
    }

    companion object {
        private const val REQ_PICK_PHONE = 101
        private const val REQ_PICK_GENERIC = 102
        private const val REQ_PANEL_BASE = 400
        private const val REQ_MEDIA_PANEL_BASE = 420
        /** Three across; tile height is measured before calculating pages. */
        private const val DRAWER_COLUMNS = AppGrid.COLUMNS
        /** Home keeps three complete rows; extra applications use the next page. */
        private const val DRAWER_MAX_ROWS = 3
        private const val DRAWER_DOT_DP = 8f
        private const val DRAWER_DOT_TOUCH_DP = 32f
        /** Below this a fling is a mis-swipe, not a page turn. */
        private const val DRAWER_SWIPE_DP = 40f
        /** ~30fps — smooth hundredths without burning CPU on a slow SoC. */
        private const val CHRONO_TICK_MS = 33L
    }
}
