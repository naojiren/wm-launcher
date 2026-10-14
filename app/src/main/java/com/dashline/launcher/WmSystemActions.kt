// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.app.ActivityManager
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.InvocationTargetException
import org.json.JSONObject

/** Short-lived app_process entry point. No exported component or persistent root service. */
object WmSystemActions {
    const val PREFIX = "WM_HOME_RESULT="
    private const val OEM = "com.wm.launcher"
    private const val OEM_HOME = "com.wm.launcher.launcher2.Launcher2"

    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Build.VERSION.SDK_INT == 23) { "此功能仅适配威马 Android 6.0.1 系统" }
            check(Os.getuid() == 0) { "当前调试服务没有系统修改权限" }
            require(args.isNotEmpty() && args[0] in setOf("status", "ensure-on", "card-on", "card-off", "home-on", "home-off", "boot-home"))
            require(args.size == if (args[0] == "boot-home") 3 else 2)
            val pkg = args[1]
            require(pkg in setOf("com.wmhome.launcher", "com.wm.xxqg"))
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val threadClass = Class.forName("android.app.ActivityThread")
            val thread = threadClass.getMethod("systemMain").invoke(null)
            val context = threadClass.getMethod("getSystemContext").invoke(thread) as Context
            val pm = context.packageManager
            val ownInfo = pm.getApplicationInfo(pkg, 0)
            val target = ComponentName(pkg, WmCardConfig.HOME_CLASS)
            val activity = pm.getActivityInfo(target, 0)
            check(activity.exported && activity.enabled && ownInfo.enabled) { "威马桌面入口不可用" }
            val backupDir = File(ownInfo.dataDir, "files/oem-home-backups")
            fun savedHome(component: ComponentName = target): Boolean = isHome(pm, component) &&
                (savedRule(component, true) || savedRule(component, false))
            fun prepareBackup() {
                val parent = backupDir.parentFile!!
                if (!parent.exists()) {
                    check(parent.mkdir())
                    Os.chown(parent.path, ownInfo.uid, ownInfo.uid)
                    Os.chmod(parent.path, 505) // Standard app files directory, 0771.
                }
                check(Os.stat(parent.path).st_uid == ownInfo.uid) { "桌面备份目录所属程序异常" }
                check(backupDir.mkdirs() || backupDir.isDirectory)
                Os.chown(backupDir.path, ownInfo.uid, ownInfo.uid)
                Os.chmod(backupDir.path, 448) // 0700
            }
            fun changeCard(enable: Boolean): JSONObject {
                val original = cardFile(pm)
                val initial = WmCardConfig.update(original.readBytes(), pkg, enable)
                if (!initial.bytes.contentEquals(original.readBytes())) {
                    // Stop its cached writer before reading the final bytes. Settings stays on screen.
                    val stop = ProcessBuilder("/system/bin/am", "force-stop", OEM).redirectErrorStream(true).start()
                    val stopOutput = stop.inputStream.bufferedReader().readText()
                    check(stop.waitFor() == 0 && !stopOutput.contains("Exception")) { "无法暂停原厂桌面" }
                    val before = original.readBytes()
                    val update = WmCardConfig.update(before, pkg, enable)
                    val metadata = Os.stat(original.path)
                    prepareBackup()
                    val backup = File(backupDir, "message-card-${System.currentTimeMillis()}.pb")
                    atomicWrite(backup, before, ownInfo.uid, ownInfo.uid, 384)
                    try {
                        atomicWrite(original, update.bytes, metadata.st_uid, metadata.st_gid, metadata.st_mode and 511)
                        val verified = readCard(pm, pkg)
                        check(verified.enabled == enable && original.readBytes().contentEquals(update.bytes))
                    } catch (failure: Throwable) {
                        atomicWrite(original, before, metadata.st_uid, metadata.st_gid, metadata.st_mode and 511)
                        throw failure
                    }
                }
                val verified = readCard(pm, pkg)
                check(verified.enabled == enable) { "原厂卡片未保存修改" }
                return JSONObject().put("cardEnabled", verified.enabled).put("cards", verified.cards)
                    .put("homeEnabled", isHome(pm, target))
            }
            fun changeHome(enable: Boolean): JSONObject {
                val candidates = ArrayList<ResolveInfo>()
                val previous = pm.javaClass.getMethod("getHomeActivities", List::class.java)
                    .invoke(pm, candidates) as? ComponentName
                val restoreFile = File(backupDir, "home-before.json")
                val selected = if (enable) target else {
                    val saved = if (restoreFile.isFile) ComponentName.unflattenFromString(
                        JSONObject(restoreFile.readText()).optString("component")) else null
                    (saved?.takeIf { it != target && hasCandidate(candidates, it) }
                        ?: ComponentName(OEM, OEM_HOME)).also {
                        check(hasCandidate(candidates, it)) { "原默认桌面不可用，已保留威马桌面" }
                    }
                }
                check(hasCandidate(candidates, selected)) { "所选程序没有系统桌面入口" }
                if (enable && previous != target) {
                    prepareBackup()
                    atomicWrite(restoreFile, JSONObject().put("component", previous?.flattenToString() ?: "")
                        .toString().toByteArray(), ownInfo.uid, ownInfo.uid, 384)
                }
                try {
                    if (enable) {
                        if (!savedRule(target, true) || !isHome(pm, target)) {
                            val pinned = persistentHome(true, pkg)
                            if (pinned) {
                                check(awaitSaved(target, true) && isHome(pm, target)) { "持久默认桌面未保存或未生效" }
                            } else {
                                // Firmware lacking the persistent API still gets a verified normal default.
                                selectHome(pm, candidates, selected)
                                check(awaitSaved(selected, false)) { "默认桌面未保存，启动后将继续重试" }
                            }
                        }
                    } else {
                        check(persistentHome(false, pkg)) { "无法撤销桌面的持久首选" }
                        check(awaitSaved(target, true, false)) { "持久首选的撤销尚未保存" }
                        selectHome(pm, candidates, selected)
                        check(awaitSaved(selected, false)) { "原默认桌面未保存" }
                    }
                    check(savedHome(selected)) { "系统没有保存并采用所选默认桌面" }
                } catch (failure: Throwable) {
                    if (enable && previous != target) persistentHome(false, pkg)
                    if (previous != null && hasCandidate(candidates, previous)) selectHome(pm, candidates, previous)
                    throw failure
                }
                return JSONObject().put("homeEnabled", savedHome()).put("homePersistent", savedRule(target, true))
            }
            val result = when (args[0]) {
                "boot-home" -> {
                    val until = args[2].toLong()
                    val cancel = File(ownInfo.dataDir, "files/${WmBootHome.CANCEL_FILE}")
                    val cancelled = cancel.isFile && cancel.length() <= 32 && cancel.readText() == until.toString()
                    @Suppress("DEPRECATION")
                    val top = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                        .getRunningTasks(1).firstOrNull()?.topActivity
                    val open = WmBootHome.shouldOpen(top, until, SystemClock.elapsedRealtime(), cancelled)
                    if (open) {
                        // app_process has no registered ActivityThread caller; use Android 6's
                        // ActivityManager command entry with a null caller, in this root helper only.
                        val output = WmCommandProcess.run(listOf("/system/bin/am", "start",
                            "-a", Intent.ACTION_MAIN, "-c", Intent.CATEGORY_HOME,
                            "-f", "0x10200000", "-n", target.flattenToString()), 5000)
                        check(!output.contains("Error:") && !output.contains("Exception")) { "开机桌面未打开" }
                    }
                    JSONObject().put("bootHomeOpened", open)
                }
                "status" -> JSONObject().put("cardEnabled", readCard(pm, pkg).enabled)
                    .put("homeEnabled", savedHome())
                "ensure-on" -> WmHomeRepair.ensure(
                    home = { savedHome() }, enableHome = { changeHome(true); Unit },
                    card = { readCard(pm, pkg).enabled }, enableCard = { changeCard(true); Unit },
                    upgradeHome = { !savedRule(target, true) })
                "card-on", "card-off" -> changeCard(args[0] == "card-on")
                else -> changeHome(args[0] == "home-on")
            }
            result.put("homePersistent", savedRule(target, true) && isHome(pm, target))
            result.put("ok", true)
            println(PREFIX + result)
        } catch (failure: Throwable) {
            var cause = failure
            while (cause is InvocationTargetException && cause.targetException != null) cause = cause.targetException
            println(PREFIX + JSONObject().put("ok", false).put("message", cause.message ?: cause.javaClass.simpleName))
        }
        // ActivityThread creates framework threads; this command must release them immediately.
        kotlin.system.exitProcess(0)
    }

    private fun isHome(pm: PackageManager, target: ComponentName): Boolean = listOf(false, true).all { defaults ->
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        if (defaults) intent.addCategory(Intent.CATEGORY_DEFAULT)
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.let {
            ComponentName(it.packageName, it.name) == target
        } == true
    }
    private fun savedRule(target: ComponentName, persistent: Boolean): Boolean = runCatching {
        File("/data/system/users/0/package-restrictions.xml").reader().use {
            WmHomeStore.contains(it, target, persistent)
        }
    }.getOrDefault(false) // PackageManager may be atomically replacing this file while we read it.
    private fun awaitSaved(target: ComponentName, persistent: Boolean, expected: Boolean = true): Boolean {
        // Android 6 writes these settings asynchronously (normally after ten seconds).
        repeat(75) {
            if (savedRule(target, persistent) == expected) return true
            if (it % 10 == 0) println("WM_HOME_SAVING") // Keep a finite local-ADB command stream alive.
            Thread.sleep(200)
        }
        return savedRule(target, persistent) == expected
    }
    private fun persistentHome(enable: Boolean, pkg: String): Boolean {
        val output = WmCommandProcess.run(listOf("/system/bin/app_process", "/system/bin",
            "com.dashline.launcher.WmPersistentHome", if (enable) "on" else "off", pkg), 5000)
        return output.lineSequence().any { it == "WM_HOME_PIN_OK" }
    }
    private fun hasCandidate(candidates: List<ResolveInfo>, target: ComponentName) = candidates.any {
        ComponentName(it.activityInfo.packageName, it.activityInfo.name) == target
    }
    private fun selectHome(pm: PackageManager, candidates: List<ResolveInfo>, target: ComponentName) {
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
        }
        val set = candidates.map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }.toTypedArray()
        // Android 6's PackageManager forwards this to the permission-checked Binder with user 0.
        pm.javaClass.getMethod("replacePreferredActivity", IntentFilter::class.java, Int::class.javaPrimitiveType,
            Array<ComponentName>::class.java, ComponentName::class.java)
            .invoke(pm, filter, candidates.maxOf { it.match }, set, target)
    }
    private fun cardFile(pm: PackageManager): File {
        val app = pm.getApplicationInfo(OEM, 0)
        return File(app.dataDir, "files/datastore/message_card.preferences_pb").also {
            val stat = Os.lstat(it.path)
            check(OsConstants.S_ISREG(stat.st_mode) && stat.st_uid == app.uid && stat.st_size in 1..1_048_576) {
                "原厂卡片文件或所属程序异常"
            }
        }
    }
    private fun readCard(pm: PackageManager, pkg: String) = WmCardConfig.inspect(cardFile(pm).readBytes(), pkg)

    private fun atomicWrite(file: File, bytes: ByteArray, uid: Int, gid: Int, mode: Int) {
        val temp = File(file.parentFile, file.name + ".wmhome-tmp")
        if (temp.exists()) check(temp.delete())
        try {
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            Os.chown(temp.path, uid, gid)
            Os.chmod(temp.path, mode)
            if (file.exists()) {
                val selinux = Class.forName("android.os.SELinux")
                if (selinux.getMethod("isSELinuxEnabled").invoke(null) == true) {
                    val label = selinux.getMethod("getFileContext", String::class.java).invoke(null, file.path) as String
                    check(selinux.getMethod("setFileContext", String::class.java, String::class.java)
                        .invoke(null, temp.path, label) == true) { "无法保留原厂文件权限" }
                }
            }
            Os.rename(temp.path, file.path)
            val directory = Os.open(file.parentFile!!.path, OsConstants.O_RDONLY, 0)
            try { Os.fsync(directory) } finally { Os.close(directory) }
        } finally { if (temp.exists()) temp.delete() }
    }
}
