// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class WmBootHomeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val original = ComponentName("com.wm.launcher", "com.wm.launcher.launcher2.Launcher2")
    private fun installOriginal() {
        shadowOf(context.packageManager).installPackage(PackageInfo().apply {
            packageName = "com.wm.launcher"
            applicationInfo = ApplicationInfo().apply { packageName = "com.wm.launcher"; enabled = true }
        })
    }
    @Test fun bootBroadcastOpensOwnHomeImmediatelyWithNewTask() {
        installOriginal()
        WmHomeBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val intent = shadowOf(context).nextStartedActivity
        assertNotNull(intent)
        assertEquals(ComponentName(context.packageName, WmCardConfig.HOME_CLASS), intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
    @Test fun packageUpdateDoesNotPullHomeOverCurrentApplication() {
        installOriginal()
        WmHomeBootReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertNull(shadowOf(context).nextStartedActivity)
    }
    @Test fun unrelatedBroadcastAndOrdinaryAndroidDoNotOpenHome() {
        WmHomeBootReceiver().onReceive(context, Intent(Intent.ACTION_SCREEN_ON))
        WmHomeBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(context).nextStartedActivity)
    }
    @Test @Config(sdk = [25]) fun integrationDoesNotRunOnAnotherAndroidVersion() {
        installOriginal()
        WmHomeBootReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(context).nextStartedActivity)
    }
    @Test fun lateOriginalHomeIsReturnedOnlyInsideBootWindow() {
        assertTrue(WmBootHome.shouldOpen(original, 15000, 5000, false))
        assertFalse(WmBootHome.shouldOpen(original, 15000, 15000, false))
        assertFalse(WmBootHome.shouldOpen(original, 15000, 5000, true))
        assertFalse(WmBootHome.shouldOpen(original, 60000, 5000, false))
    }
    @Test fun otherAppsSettingsAndAlreadyShownHomeArePreserved() {
        for (top in listOf(null, ComponentName("remote.carplay", "Remote"),
            ComponentName(context.packageName, WmCardConfig.HOME_CLASS),
            ComponentName(context.packageName, "com.dashline.launcher.SettingsActivity"),
            ComponentName("com.wm.launcher", "OtherActivity"))) {
            assertFalse(WmBootHome.shouldOpen(top, 15000, 5000, false))
        }
    }
    @Test fun intentionalAppLaunchCancelsTheBootHandoffForRootHelperToo() {
        installOriginal(); WmBootHome.begin(context)
        val service = shadowOf(context).nextStartedService
        val until = service.getLongExtra(WmBootHome.DEADLINE_EXTRA, 0L)
        assertTrue(until > SystemClock.elapsedRealtime())
        WmBootHome.cancel(context)
        assertEquals(until.toString(), File(context.filesDir, WmBootHome.CANCEL_FILE).readText())
    }
}
