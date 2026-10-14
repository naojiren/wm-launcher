// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dashline.launcher.databinding.ActivitySettingsBinding

/** Launcher settings: change which app each role uses. */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    /** Guards the row's label while a manual check is in flight. */
    private var checkingUpdates = false
    private var homeActionBusy = false

    private companion object {
        const val REQ_DEFAULT_LAUNCHER = 10
        /** Long enough for a slow unit to answer before we call it up to date. */
        const val CHECK_FEEDBACK_MS = 12_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = Prefs(this)

        for (view in listOf(binding.rowWmWeike, binding.wmWeikeSwitch,
            binding.rowWmDefaultHome, binding.wmDefaultHomeSwitch)) {
            view.setOnClickListener { runHomeAction("ensure-on", true) }
        }

        binding.header.pageTitle.setText(R.string.settings_title)
        SettingsTextSizing.enlarge(binding.root)
        bindPageNav(binding.header.navBack, binding.header.navHome)
        binding.rowFactoryApps.setOnClickListener { startActivity(Intent(this, FactoryAppsActivity::class.java)) }
        binding.rowWmOrientation.setOnClickListener {
            val values = arrayOf("auto", "portrait", "landscape")
            val labels = arrayOf(getString(R.string.wm_orientation_auto), getString(R.string.wm_orientation_portrait),
                getString(R.string.wm_orientation_landscape))
            SettingsTextSizing.show(AlertDialog.Builder(this).setTitle(R.string.wm_orientation).setItems(labels) { _, index ->
                getSharedPreferences("radio_launcher", MODE_PRIVATE).edit().putString("wm_orientation", values[index]).apply()
                recreate()
            })
        }
        binding.rowWmSystemBars.setOnClickListener {
            val sp = getSharedPreferences("radio_launcher", MODE_PRIVATE)
            sp.edit().putBoolean("wm_show_system_bars", !sp.getBoolean("wm_show_system_bars", false)).apply()
            recreate()
        }
        binding.rowWmLicense.setOnClickListener {
            SettingsTextSizing.show(AlertDialog.Builder(this).setTitle(R.string.wm_license)
                .setMessage(getString(R.string.wm_license_message, BuildConfig.VERSION_NAME))
                .setPositiveButton(android.R.string.ok, null))
        }

        binding.rowLanguage.setOnClickListener { showLanguageDialog() }
        binding.rowPhone.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_PHONE, 1)
        }
        binding.rowNav.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_NAV, 2)
        }
        binding.rowRadio.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_RADIO, 3)
        }
        binding.rowMedia.setOnClickListener {
            AppPickerActivity.start(this, Prefs.ROLE_MEDIA, 5)
        }
        binding.rowAndroidAuto.setOnClickListener {
            AppPickerActivity.start(this, "factory:vehicle", 6)
        }
        binding.rowCarPlay.setOnClickListener {
            AppPickerActivity.start(this, "factory:energy", 7)
        }
        binding.rowNotificationAccess.setOnClickListener {
            NotificationAccess.openSettings(this)
        }
        binding.rowTheme.setOnClickListener { showThemeDialog() }
        binding.rowCustomize.setOnClickListener {
            startActivity(Intent(this, CustomizeActivity::class.java))
        }
        binding.rowClockStyle.setOnClickListener { showClockStyleDialog() }
        binding.rowChrono.setOnClickListener {
            prefs.chronoEnabled = !prefs.chronoEnabled
            refresh()
        }
        binding.rowTabs.setOnClickListener {
            startActivity(Intent(this, TabOrderActivity::class.java))
        }
        binding.rowGradient.setOnClickListener {
            startActivity(Intent(this, GradientPickerActivity::class.java))
        }
        binding.rowKeepOn.setOnClickListener {
            prefs.keepScreenOn = !prefs.keepScreenOn
            refresh()
        }
        binding.rowDefaultLauncher.setOnClickListener {
            DefaultLauncher.request(this, REQ_DEFAULT_LAUNCHER)
        }
        binding.rowCheckUpdates.setOnClickListener { checkForUpdates() }
        binding.rowResetHidden.setOnClickListener {
            prefs.clearHiddenApps()
            refresh()
        }

        refresh()
        runHomeAction("ensure-on", false)
    }

    override fun onResume() {
        super.onResume()
        refresh()  // reflect notification-access / default-launcher changes on return
    }

    private fun showLanguageDialog() {
        val codes = listOf("") + LocaleManager.LANGUAGES.map { it.code }
        val labels = (listOf(getString(R.string.language_system)) +
            LocaleManager.LANGUAGES.map { it.name }).toTypedArray()
        SettingsTextSizing.show(AlertDialog.Builder(this)
            .setTitle(R.string.settings_language)
            .setItems(labels) { _, which ->
                prefs.language = codes[which]
                // Relaunch the launcher so every screen re-inflates in the new language.
                val intent = Intent(this, HomeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(intent)
                finish()
            }
        )
    }

    /** Small helper for the single-choice dialogs this screen is full of. */
    private fun pick(titleRes: Int, options: List<Pair<String, Int>>, onPick: (String) -> Unit) {
        val labels = options.map { getString(it.second) }.toTypedArray()
        SettingsTextSizing.show(AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setItems(labels) { _, which ->
                onPick(options[which].first)
                refresh()
            }
        )
    }

    private fun showClockStyleDialog() {
        val styles = listOf(
            Prefs.CLOCK_DIGITAL to R.string.clock_digital,
            Prefs.CLOCK_ANALOG to R.string.clock_analog,
            Prefs.CLOCK_MINIMAL to R.string.clock_minimal
        )
        val labels = styles.map { getString(it.second) }.toTypedArray()
        SettingsTextSizing.show(AlertDialog.Builder(this)
            .setTitle(R.string.settings_clock_style)
            .setItems(labels) { _, which ->
                prefs.clockStyle = styles[which].first
                refresh()
            }
        )
    }

    private fun showThemeDialog() {
        val modes = listOf(
            Prefs.THEME_AUTO to R.string.theme_auto,
            Prefs.THEME_LIGHT to R.string.theme_light,
            Prefs.THEME_DARK to R.string.theme_dark,
            Prefs.THEME_SYSTEM to R.string.theme_system
        )
        val labels = modes.map { getString(it.second) }.toTypedArray()
        SettingsTextSizing.show(AlertDialog.Builder(this)
            .setTitle(R.string.settings_theme)
            .setItems(labels) { _, which ->
                prefs.themeMode = modes[which].first
                ThemeManager.apply(prefs)  // recreates activities as needed
                refresh()
            }
        )
    }

    /**
     * A manual check, so a user can confirm they're current rather than having to
     * infer it from the absence of a prompt. Unlike the automatic one this
     * ignores the once-a-day limit and says something either way.
     */
    private fun checkForUpdates() {
        if (!UpdateChecker.isSupported(this)) return
        if (checkingUpdates) return
        checkingUpdates = true
        binding.checkUpdatesValue.setText(R.string.update_checking)

        var answered = false
        UpdateChecker.check(this, force = true) { update ->
            answered = true
            checkingUpdates = false
            refresh()
            UpdatePrompt.show(this, update)
        }
        // The check is silent when there's nothing to report — including when it
        // fails — so the row has to resolve itself either way.
        binding.checkUpdatesValue.postDelayed({
            if (!answered) {
                checkingUpdates = false
                binding.checkUpdatesValue.setText(R.string.update_up_to_date)
            }
        }, CHECK_FEEDBACK_MS)
    }

    private fun refresh() {
        val sp = getSharedPreferences("radio_launcher", MODE_PRIVATE)
        // These switches show the always-enabled policy, not a possibly pending system state.
        binding.wmWeikeSwitch.isChecked = true
        binding.wmDefaultHomeSwitch.isChecked = true
        for (view in listOf(binding.rowWmWeike, binding.wmWeikeSwitch,
            binding.rowWmDefaultHome, binding.wmDefaultHomeSwitch)) view.isEnabled = !homeActionBusy
        binding.wmOrientationValue.setText(when (sp.getString("wm_orientation", "auto")) {
            "portrait" -> R.string.wm_orientation_portrait
            "landscape" -> R.string.wm_orientation_landscape
            else -> R.string.wm_orientation_auto
        })
        binding.wmSystemBarsValue.setText(if (sp.getBoolean("wm_show_system_bars", false)) R.string.on else R.string.off)
        binding.languageValue.text = LocaleManager.displayName(this, prefs.language)
        binding.phoneValue.text = label(prefs.phoneApp)
        binding.navValue.text = label(prefs.navApp)
        binding.radioValue.text = label(prefs.radioApp)
        binding.mediaValue.text = label(prefs.mediaApp)
        binding.androidAutoValue.text = label(WmFactory.target(this, WmFactory.entries.first { it.id == "vehicle" }))
        binding.carPlayValue.text = label(WmFactory.target(this, WmFactory.entries.first { it.id == "energy" }))
        binding.notificationAccessValue.text = getString(
            if (NotificationAccess.isGranted(this)) R.string.access_enabled
            else R.string.access_disabled
        )
        binding.themeValue.text = getString(themeLabel(prefs.themeMode))
        binding.tabsValue.text = getString(R.string.tabs_count, prefs.visibleTabs().size)
        binding.clockStyleValue.text = getString(when (prefs.clockStyle) {
            Prefs.CLOCK_ANALOG -> R.string.clock_analog
            Prefs.CLOCK_MINIMAL -> R.string.clock_minimal
            else -> R.string.clock_digital
        })
        binding.chronoValue.text = getString(if (prefs.chronoEnabled) R.string.on else R.string.off)

        val gradient = GradientThemes.current(this)
        binding.gradientValue.setText(gradient.nameRes)
        binding.gradientSwatch.background = GradientThemes.swatch(
            this, gradient, 4f * resources.displayMetrics.density
        )
        binding.keepOnValue.text = getString(if (prefs.keepScreenOn) R.string.on else R.string.off)
        binding.defaultLauncherValue.text = getString(
            if (DefaultLauncher.isDefault(this)) R.string.yes else R.string.set_now
        )
        binding.resetHiddenValue.text = getString(R.string.hidden_count, prefs.hiddenApps.size)

        binding.versionValue.text = getString(
            R.string.settings_version_value,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
        // The Play build has no business offering its own updates.
        val updatable = UpdateChecker.isSupported(this)
        binding.rowCheckUpdates.visibility =
            if (updatable) android.view.View.VISIBLE else android.view.View.GONE
        binding.updateDivider.visibility =
            if (updatable) android.view.View.VISIBLE else android.view.View.GONE
        if (updatable && !checkingUpdates) {
            binding.checkUpdatesValue.text = lastCheckedLabel()
        }
    }

    /** "Never" until the first check, then how long ago it ran. */
    private fun lastCheckedLabel(): String {
        val last = prefs.lastUpdateCheck
        if (last <= 0L) return getString(R.string.update_never_checked)
        val hours = (System.currentTimeMillis() - last) / (60 * 60 * 1000L)
        return when {
            hours < 1 -> getString(R.string.update_checked_recently)
            hours < 24 -> getString(R.string.update_checked_hours, hours.toInt())
            else -> getString(R.string.update_checked_days, (hours / 24).toInt())
        }
    }

    private fun themeLabel(mode: String): Int = when (mode) {
        Prefs.THEME_LIGHT -> R.string.theme_light
        Prefs.THEME_DARK -> R.string.theme_dark
        Prefs.THEME_SYSTEM -> R.string.theme_system
        else -> R.string.theme_auto
    }

    private fun label(pkg: String?): String =
        AppRepository.labelFor(this, pkg) ?: getString(R.string.not_set)

    private fun runHomeAction(action: String, showFeedback: Boolean) {
        if (homeActionBusy) return
        homeActionBusy = true
        binding.wmWeikeSummary.setText(R.string.wm_home_checking)
        binding.wmDefaultHomeSummary.setText(R.string.wm_home_checking)
        refresh()
        WmHomeIntegration.run(this, action) { result ->
            if (isFinishing || isDestroyed) return@run
            homeActionBusy = false
            val value = result.getOrNull()
            fun description(enabled: Boolean, resource: Int) = getString(resource) + "\n" +
                getString(if (enabled) R.string.wm_home_verified else R.string.wm_home_unverified)
            binding.wmWeikeSummary.text = description(value?.optBoolean("cardEnabled") == true,
                R.string.wm_weike_entry_description)
            binding.wmDefaultHomeSummary.text = description(value?.optBoolean("homeEnabled") == true,
                R.string.wm_default_home_description)
            if (value?.optBoolean("homeEnabled") == true && !value.optBoolean("homePersistent")) {
                binding.wmDefaultHomeSummary.append("\n" + getString(R.string.wm_home_normal_fallback))
            }
            refresh()
            if (showFeedback) {
                if (value?.optBoolean("complete") == true) {
                    android.widget.Toast.makeText(this, R.string.wm_home_auto_saved,
                        android.widget.Toast.LENGTH_LONG).show()
                } else {
                    SettingsTextSizing.show(AlertDialog.Builder(this).setTitle(R.string.wm_home_failed)
                        .setMessage(result.exceptionOrNull()?.message ?: value?.optString("message")
                            ?: getString(R.string.wm_home_unverified))
                        .setPositiveButton(android.R.string.ok, null))
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }
}
