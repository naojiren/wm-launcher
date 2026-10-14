// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.app.IntentService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Finite startup work; IntentService stops itself when the bounded repair finishes. */
@Suppress("DEPRECATION")
class WmHomeRepairService : IntentService("WM-StartupHome") {
    override fun onHandleIntent(intent: Intent?) {
        val until = intent?.getLongExtra(WmBootHome.DEADLINE_EXTRA, 0L) ?: 0L
        if (until > 0L) WmBootHome.finish(until) {
            WmHomeIntegration.checkBootHome(applicationContext, it)
        }
        WmHomeIntegration.repairStartup(applicationContext)
    }
}

class WmHomeBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) WmBootHome.begin(context)
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED))
            WmHomeIntegration.ensureOnStart(context)
    }
}
