// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.system.Os

/** Root-only, short-lived helper. Drops to system UID before opening the package Binder. */
object WmPersistentHome {
    @JvmStatic fun main(args: Array<String>) {
        try {
            check(Build.VERSION.SDK_INT == 23 && Os.getuid() == 0)
            require(args.size == 2 && args[0] in setOf("on", "off"))
            require(args[1] in setOf("com.wmhome.launcher", "com.wm.xxqg"))
            // Android 6 explicitly rejects root for this API: it requires UID 1000.
            // Change only this disposable helper; the launcher never gets a system UID.
            Os.setgid(1000)
            Os.setuid(1000)
            check(Os.getuid() == 1000)
            if (Looper.myLooper() == null) Looper.prepareMainLooper()
            val binder = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java).invoke(null, "package") as IBinder
            check(binder.interfaceDescriptor == "android.content.pm.IPackageManager")
            val pm = Class.forName("android.content.pm.IPackageManager\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            val api = Class.forName("android.content.pm.IPackageManager")
            if (args[0] == "on") {
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
                    priority = 1000
                }
                api.getMethod("addPersistentPreferredActivity", IntentFilter::class.java,
                    ComponentName::class.java, Int::class.javaPrimitiveType)
                    .invoke(pm, filter, ComponentName(args[1], WmCardConfig.HOME_CLASS), 0)
            } else {
                // Remove only our own rules, preserving every other application's defaults.
                api.getMethod("clearPackagePersistentPreferredActivities", String::class.java,
                    Int::class.javaPrimitiveType).invoke(pm, args[1], 0)
            }
            println("WM_HOME_PIN_OK")
        } catch (failure: Throwable) {
            println("WM_HOME_PIN_FAILED: ${failure.cause?.message ?: failure.message}")
        }
        kotlin.system.exitProcess(0)
    }
}
