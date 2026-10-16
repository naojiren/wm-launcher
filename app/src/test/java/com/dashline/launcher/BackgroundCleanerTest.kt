// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.app.ActivityManager
import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class BackgroundCleanerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun app(name: String, id: Int, mask: Int = 0) = ApplicationInfo().apply {
        packageName = name; uid = id; flags = mask; sourceDir = "/data/app/$name/base.apk"
    }
    private val own get() = app(context.packageName, 12000)
    private fun process(id: Int, vararg packages: String) = ActivityManager.RunningAppProcessInfo().apply {
        uid = id; pkgList = packages.toList().toTypedArray()
    }
    @Test fun preservesBothDesktopsAndAllKindsOfSystemFactoryPackages() {
        val apps = listOf(own, app("com.wm.xxqg", 12001), app("android", 1000),
            app("example.system", 12002, ApplicationInfo.FLAG_SYSTEM),
            app("example.updated", 12003, ApplicationInfo.FLAG_UPDATED_SYSTEM_APP),
            app("example.persistent", 12004, ApplicationInfo.FLAG_PERSISTENT),
            app("com.ivi.musicplayer", 12005), app("com.yftech.btservice", 12006),
            app("com.wm.launcher", 12007), app("com.living.engine", 12008),
            app("example.vendor", 12009).apply { sourceDir = "/vendor/app/example.apk" },
            app("example.otheruser", 112010), app("example.player", 12011))
        assertEquals(listOf("example.player"), WmCleanupPlan.targets(apps, context.packageName).map { it.packageName })
        assertTrue(WmCleanupPlan.targets(apps, "missing.desktop").isEmpty())
    }
    @Test fun sharingProtectedUidProtectsEveryPackageEvenWithoutSystemFlags() {
        val apps = listOf(own, app("example.desktoppeer", 12000),
            app("example.system", 12001, ApplicationInfo.FLAG_SYSTEM), app("example.peer", 12001),
            app("example.a", 12002), app("example.b", 12002))
        assertEquals(listOf("example.a", "example.b"),
            WmCleanupPlan.targets(apps, context.packageName).map { it.packageName })
    }
    @Test fun onlyRunningThirdPartyPackagesAreForceStoppedAndUnknownSharedProcessesAreExcluded() {
        val apps = listOf(own, app("example.music", 12001), app("example.idle", 12002),
            app("example.unknownpeer", 12003), app("example.noiconservice", 12004))
        val running = listOf(process(12000, context.packageName), process(12001, "example.music"),
            process(12003, "example.unknownpeer", "unknown.service"), process(12004))
        assertEquals(listOf("example.music", "example.noiconservice"),
            WmCleanupPlan.runningTargets(apps, context.packageName, running).map { it.packageName })
    }
    @Test fun aNormalInstallUsesBackgroundApiAndReportsRequestsRatherThanInventingKilledCounts() {
        val pm = shadowOf(context.packageManager)
        // Robolectric's installed application UID is used to preserve the actual desktop.
        val ownId = context.applicationInfo.uid
        pm.installPackage(android.content.pm.PackageInfo().apply { packageName = "example.cleanable";
            applicationInfo = app(packageName, if (ownId == 12021) 12022 else 12021) })
        pm.installPackage(android.content.pm.PackageInfo().apply { packageName = "example.protected";
            applicationInfo = app(packageName, 12023, ApplicationInfo.FLAG_SYSTEM) })
        val calls = mutableListOf<String>()
        val result = WmBackgroundCleaner.clean(context, root = { error("ADB unavailable") }, kill = { calls += it })
        assertTrue(calls.contains("example.cleanable")); assertFalse(calls.contains("example.protected"))
        assertFalse(calls.contains(context.packageName)); assertFalse(result.strong)
        assertEquals(calls.size, result.requested); assertTrue(result.message().contains("请求"))
    }
    @Test fun eachFailureIsCountedAndDoesNotAbortRemainingNativeCleanup() {
        val pm = shadowOf(context.packageManager)
        for ((index, name) in listOf("example.a", "example.b").withIndex()) {
            pm.installPackage(android.content.pm.PackageInfo().apply { packageName = name;
                applicationInfo = app(name, 12100 + index) })
        }
        val calls = mutableListOf<String>()
        val result = WmBackgroundCleaner.clean(context, root = { error("denied") }, kill = {
            calls += it; if (it == "example.a") throw SecurityException()
        })
        assertEquals(listOf("example.a", "example.b"), calls)
        assertEquals(1, result.requested); assertEquals(1, result.failed)
        assertTrue(result.message().contains("1 个未完成"))
    }
    @Test fun validatedRootResultAvoidsDuplicateFallbackAndInvalidCountsAreRejected() {
        val result = WmBackgroundCleaner.clean(context,
            root = { "WM_VEHICLE_RESULT={\"action\":\"clean-background\",\"ok\":true,\"requested\":2,\"failed\":0}" },
            kill = { error("must not send a second kill") })
        assertEquals(2, result.requested); assertTrue(result.strong)
        assertEquals("没有需要清理的第三方后台应用", WmCleanupResult(0, strong = true).message())
        for (count in listOf("-1", "1.5", "\"2\"", "10001", "null")) {
            assertTrue(runCatching { WmCleanupResult.decode("WM_VEHICLE_RESULT={\"action\":\"clean-background\",\"ok\":true,\"requested\":$count,\"failed\":0}") }.isFailure)
        }
    }
}
