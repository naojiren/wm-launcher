// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Also protects updated system apps, factory data apps and every package sharing their UID. */
internal object WmCleanupPlan {
    fun targets(apps: List<ApplicationInfo>, ownPackage: String): List<ApplicationInfo> {
        val own = apps.firstOrNull { it.packageName == ownPackage } ?: return emptyList()
        val protectedUids = apps.filter { app ->
            app.packageName == ownPackage || app.packageName in setOf("com.wmhome.launcher", "com.wm.xxqg") ||
                app.uid % 100000 < 10000 ||
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP or
                    ApplicationInfo.FLAG_PERSISTENT) != 0 ||
                WmFactory.entries.any { it.packageName == app.packageName } ||
                listOf("com.wm.", "com.wmhome.", "com.ivi.", "com.yftech.", "com.living.")
                    .any { app.packageName.startsWith(it) } ||
                listOf("/system/", "/vendor/", "/oem/").any { app.sourceDir?.startsWith(it) == true }
        }.map { it.uid }.toSet()
        return apps.filter { it.uid !in protectedUids && it.uid / 100000 == own.uid / 100000 }
            .distinctBy { it.packageName }.sortedBy { it.packageName }
    }

    fun runningTargets(apps: List<ApplicationInfo>, ownPackage: String,
        processes: List<ActivityManager.RunningAppProcessInfo>): List<ApplicationInfo> {
        val eligible = targets(apps, ownPackage)
        val eligibleNames = eligible.map { it.packageName }.toSet()
        // A mixed/unknown process must not cause an OEM or shared process to be killed.
        val unsafeUids = processes.filter { p -> p.pkgList?.any { it !in eligibleNames } == true }
            .map { it.uid }.toSet()
        val runningUids = processes.map { it.uid }.toSet() - unsafeUids
        return eligible.filter { it.uid in runningUids }
    }
}

internal data class WmCleanupResult(val requested: Int, val failed: Int = 0,
    val strong: Boolean = false, val interrupted: Boolean = false) {
    fun message(): String = when {
        interrupted -> "已清理 $requested 个第三方后台应用；已离开首页，停止继续清理"
        failed > 0 -> "已${if (strong) "清理" else "请求清理"} $requested 个第三方应用，$failed 个未完成"
        strong && requested == 0 -> "没有需要清理的第三方后台应用"
        strong -> "已清理 $requested 个第三方后台应用"
        requested == 0 -> "没有可清理的第三方应用"
        else -> "已请求清理 $requested 个第三方应用后台；系统可能保留正在使用的服务"
    }

    companion object {
        fun decode(output: String): WmCleanupResult {
            val value = WmVehicleRoot.result(output)
            check(value.optBoolean("ok") && value.optString("action") == "clean-background")
            fun count(name: String): Int {
                val raw = value.get(name) as? Number ?: error("清理结果无效")
                return raw.toInt().also { check(it in 0..10000 && raw.toDouble() == it.toDouble()) }
            }
            return WmCleanupResult(count("requested"), count("failed"), true, value.optBoolean("interrupted"))
        }
    }
}

/** One short worker per explicit tap. No boot cleanup, timer, process watcher or resident service. */
internal object WmBackgroundCleaner {
    private val busy = AtomicBoolean(false)
    fun start(context: Context, finished: (String) -> Unit): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val message = try { clean(app).message() }
            catch (_: Exception) { "后台清理未完成，请稍后重试" }
            finally { busy.set(false) }
            main.post { finished(message) }
        }, "WM-CleanBackground").start()
        return true
    }

    internal fun clean(context: Context,
        root: () -> String = { WmVehicleRoot.execute(context, "clean-background") },
        kill: (String) -> Unit = { pkg ->
            (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
        }): WmCleanupResult {
        // Android 6 root can also finish third-party music/CarPlay foreground services.
        // A normal APK can still use the Android background-process API without ADB.
        if (Build.VERSION.SDK_INT == 23 && context.applicationInfo.uid < 100000) {
            runCatching { WmCleanupResult.decode(root()) }.getOrNull()?.let { return it }
        }
        val apps = context.packageManager.getInstalledApplications(0)
        var requested = 0
        var failed = 0
        for (app in WmCleanupPlan.targets(apps, context.packageName)) {
            try { kill(app.packageName); requested++ } catch (_: Exception) { failed++ }
        }
        return WmCleanupResult(requested, failed)
    }

    /** Called only by the root-only helper; targets are derived from PackageManager, never shell text. */
    @Suppress("DEPRECATION")
    fun rootCommand(): JSONObject {
        if (Looper.myLooper() == null) Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        val context = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ownPackage = BuildConfig.APPLICATION_ID
        check(ownPackage in setOf("com.wmhome.launcher", "com.wm.xxqg"))
        val targets = WmCleanupPlan.runningTargets(pm.getInstalledApplications(0), ownPackage,
            am.runningAppProcesses ?: error("无法获取后台应用列表"))
        val stop = ActivityManager::class.java.getMethod("forceStopPackage", String::class.java)
        var requested = 0
        var failed = 0
        var interrupted = false
        for (app in targets) {
            // A tap is authorized from Home only. Stop if the user leaves Home during cleanup.
            if (am.getRunningTasks(1).firstOrNull()?.topActivity?.packageName != ownPackage) {
                interrupted = true
                break
            }
            try {
                // Re-read package flags/UID immediately before each stop, including shared-UID peers.
                val current = WmCleanupPlan.targets(pm.getInstalledApplications(0), ownPackage)
                    .firstOrNull { it.packageName == app.packageName && it.uid == app.uid } ?: continue
                stop.invoke(am, current.packageName)
                requested++
            } catch (_: Exception) { failed++ }
        }
        return JSONObject().put("requested", requested).put("failed", failed).put("interrupted", interrupted)
    }
}
