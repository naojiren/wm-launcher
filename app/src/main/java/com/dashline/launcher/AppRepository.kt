// Copyright (C) 2026 Metehan Kaygısız
// SPDX-License-Identifier: GPL-3.0-only

package com.dashline.launcher

import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Loads the list of launchable apps from PackageManager.
 * This is the heart of any launcher: query all activities that respond to
 * ACTION_MAIN + CATEGORY_LAUNCHER, which is exactly what a normal app icon does.
 */
object AppRepository {

    fun loadLaunchableApps(context: Context, exclude: Set<String> = emptySet()): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val activities = pm.queryIntentActivities(intent, 0)
        val ownPackage = context.packageName

        return activities
            .asSequence()
            .mapNotNull { resolveInfo ->
                val pkg = resolveInfo.activityInfo.packageName ?: return@mapNotNull null
                // Don't list ourselves, or apps the user has hidden.
                if (pkg == ownPackage || pkg in exclude || !resolveInfo.activityInfo.enabled ||
                    !resolveInfo.activityInfo.applicationInfo.enabled ||
                    !resolveInfo.activityInfo.exported) return@mapNotNull null
                runCatching { AppInfo(
                    label = resolveInfo.loadLabel(pm).toString(),
                    packageName = pkg,
                    icon = resolveInfo.loadIcon(pm),
                    isSystem = resolveInfo.activityInfo.applicationInfo.flags and
                        (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                    isFactory = WmFactory.entries.any { it.packageName == pkg }
                ) }.getOrNull()
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    /** Try each package in order; launch the first one that's installed. */
    fun launchFirstAvailable(context: Context, packages: List<String>): Boolean {
        for (pkg in packages) {
            if (pkg.isNotEmpty() && launch(context, pkg)) return true
        }
        return false
    }

    /** True if the package is installed and launchable. */
    fun isInstalled(context: Context, packageName: String): Boolean =
        runCatching { intentFor(context, packageName) != null }.getOrDefault(false)

    private fun component(key: String): ComponentName? =
        if ('/' in key) ComponentName.unflattenFromString(key) else null

    private fun packageOf(key: String): String = component(key)?.packageName ?: key

    private fun intentFor(context: Context, key: String): Intent? {
        val explicit = component(key)
        if (explicit != null) {
            val info = runCatching { context.packageManager.getActivityInfo(explicit, 0) }.getOrNull()
                ?: return null
            if (!info.enabled || !info.applicationInfo.enabled || !info.exported) return null
            if (info.permission != null && context.packageManager.checkPermission(
                    info.permission, context.packageName) != PackageManager.PERMISSION_GRANTED) return null
            return Intent(Intent.ACTION_MAIN).setComponent(explicit)
        }
        return context.packageManager.getLaunchIntentForPackage(key)
            ?: WmFactory.entries.firstOrNull { it.packageName == key }?.let { intentFor(context, it.key) }
    }

    /** App icon for a package, or null if it isn't installed. */
    fun iconFor(context: Context, packageName: String?): android.graphics.drawable.Drawable? {
        if (packageName.isNullOrEmpty()) return null
        return try {
            context.packageManager.getApplicationIcon(packageOf(packageName))
        } catch (e: Exception) {
            null
        }
    }

    /** Human-readable label for a package, or null if it isn't installed. */
    fun labelFor(context: Context, packageName: String?): String? {
        if (packageName.isNullOrEmpty()) return null
        WmFactory.entries.firstOrNull { it.key == packageName }?.let { return it.label }
        val pm = context.packageManager
        return try {
            pm.getApplicationLabel(pm.getApplicationInfo(packageOf(packageName), 0)).toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Launch an app by package name. Returns false if it can't be launched
     * (e.g. it was uninstalled), so the caller can show feedback.
     */
    fun launch(context: Context, packageName: String): Boolean {
        val launchIntent = runCatching { intentFor(context, packageName) }.getOrNull() ?: return false
        // Factory subpages share an affinity. Without CLEAR_TOP Android can bring
        // back EnergyActivity when the user explicitly requested vehicle control.
        val explicitFactoryPage = component(packageName) != null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
            if (explicitFactoryPage) Intent.FLAG_ACTIVITY_CLEAR_TOP
            else Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            WmBootHome.cancel(context)
            context.startActivity(launchIntent)
            Usage.record(context, packageName)
            true
        } catch (e: Exception) {
            false
        }
    }
}
