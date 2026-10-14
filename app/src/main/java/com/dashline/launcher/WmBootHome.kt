// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.io.File

/** One boot handoff, with three bounded checks for a late original launcher. */
internal object WmBootHome {
    const val DEADLINE_EXTRA = "wm_boot_home_until"
    const val CANCEL_FILE = "wm-boot-home.cancelled"
    private const val WINDOW = 15_000L
    @Volatile private var deadline = 0L

    fun applicable(context: Context): Boolean = Build.VERSION.SDK_INT == 23 && runCatching {
        context.packageManager.getApplicationInfo("com.wm.launcher", 0).enabled
    }.getOrDefault(false)

    fun homeIntent(pkg: String) = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        .setComponent(ComponentName(pkg, WmCardConfig.HOME_CLASS))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    fun begin(context: Context) {
        if (!applicable(context)) return
        deadline = SystemClock.elapsedRealtime() + WINDOW
        runCatching { File(context.filesDir, CANCEL_FILE).delete() }
        // Queue the short boot handoff before opening Home queues its normal settings repair.
        runCatching { context.startService(Intent(context, WmHomeRepairService::class.java)
            .putExtra(DEADLINE_EXTRA, deadline)) }
        runCatching { context.startActivity(homeIntent(context.packageName)) }
    }

    fun cancel(context: Context) {
        val until = deadline
        if (until <= SystemClock.elapsedRealtime()) return
        // Also visible to an already-running root helper, preserving an intentional OEM entry.
        runCatching { File(context.filesDir, CANCEL_FILE).writeText(until.toString()) }
        deadline = 0L
    }

    fun finish(until: Long, check: (Long) -> Unit) {
        for (delay in longArrayOf(2000, 3000, 5000)) {
            Thread.sleep(delay)
            if (deadline != until || SystemClock.elapsedRealtime() >= until) return
            runCatching { check(until) }
        }
    }

    fun shouldOpen(top: ComponentName?, until: Long, now: Long, cancelled: Boolean): Boolean =
        !cancelled && until > now && until - now <= WINDOW &&
            top == ComponentName("com.wm.launcher", "com.wm.launcher.launcher2.Launcher2")
}
