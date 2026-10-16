// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.Context
import android.os.Build
import android.os.IBinder
import android.system.Os
import org.json.JSONObject

internal data class WmBatteryState(val tenths: Int? = null, val charging: Boolean? = null,
    val plugged: Boolean? = null, val problem: String? = null) {
    companion object {
        fun from(read: (Int) -> Int?): WmBatteryState {
            fun anyFlag(types: List<Int>): Boolean? {
                val flags = types.map { read(it)?.takeIf { n -> n == 0 || n == 1 } }
                return when { flags.any { it == 1 } -> true; flags.all { it == 0 } -> false; else -> null }
            }
            return WmBatteryState(read(10)?.takeIf { it in 0..1000 }, anyFlag(listOf(0, 3, 7)),
                anyFlag(listOf(2, 6)))
        }
    }
}

/** Direct privileged getter first; ordinary installs can use the existing local root channel.
 * Never substitutes the Android head-unit battery for the vehicle's traction battery. */
internal class WmBatteryAccess(private val context: Context,
    private val direct: () -> WmBatteryState = { readDirect() },
    private val fallback: () -> String = { WmVehicleRoot.execute(context, "battery") }) {
    fun read(): WmBatteryState {
        if (Build.VERSION.SDK_INT != 23) return WmBatteryState(problem = "此车辆接口仅适配威马安卓 6.0.1")
        val normal = runCatching(direct).getOrDefault(WmBatteryState())
        if (normal.tenths != null) return normal
        return try {
            val root = decode(fallback())
            if (root.tenths != null) root else normal.copy(problem = "原厂电量接口暂未返回有效数值")
        } catch (_: Exception) {
            normal.copy(problem = "未获得车辆数据权限，请开启车机 ADB 并允许调试授权，或安装为系统应用")
        }
    }
    companion object {
        fun readDirect(binder: IBinder? = WmDetailBinder.service(WmBatteryReader.SERVICE)): WmBatteryState =
            WmBatteryState.from { type -> runCatching { WmBatteryReader.chargeInfo(binder, type) }.getOrNull() }
        fun decode(output: String): WmBatteryState {
            val json = WmVehicleRoot.result(output)
            check(json.optBoolean("ok") && json.optString("action") == "battery")
            val number = json.opt("soc") as? Number
            val soc = number?.toInt()?.takeIf { it in 0..1000 && number.toDouble() == it.toDouble() }
            fun flag(key: String) = json.opt(key) as? Boolean
            return WmBatteryState(soc, flag("charging"), flag("plugged"))
        }
    }
}

internal object WmVehicleRoot {
    const val PREFIX = "WM_VEHICLE_RESULT="
    private val lock = Any()
    fun execute(context: Context, action: String): String = synchronized(lock) {
        require(action in setOf("battery", "bt-status", "bt-route", "clean-background"))
        check(Build.VERSION.SDK_INT == 23)
        fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
        val command = "CLASSPATH=${quote(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
            "com.dashline.launcher.WmVehicleActions $action"
        val su = WmRootShell.find()
        if (su != null) su.execute(command, 8000) else {
            // Reading data must not restart adbd or alter system files.
            synchronized(WmLocalAdb.operationLock) {
                WmLocalAdb(context, firstReplyTimeout = 1000, operationTimeout = 8000).use { adb ->
                    adb.connect()
                    check(Regex("(?:^|\\s)uid=0\\(").containsMatchIn(adb.shell("id")))
                    adb.shell(command)
                }
            }
        }
    }
    fun result(output: String): JSONObject = JSONObject(output.lineSequence()
        .lastOrNull { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: error("未收到车辆数据"))
}

/** Closed, short-lived root helper for audited vehicle reads/audio and explicit background cleanup. */
object WmVehicleActions {
    @JvmStatic fun main(args: Array<String>) {
        val result = try {
            check(Build.VERSION.SDK_INT == 23 && Os.getuid() == 0)
            require(args.size == 1 && args[0] in setOf("battery", "bt-status", "bt-route", "clean-background"))
            val value = when (args[0]) {
                "clean-background" -> WmBackgroundCleaner.rootCommand()
                "battery" -> {
                    val state = WmBatteryAccess.readDirect()
                    JSONObject().put("soc", state.tenths ?: JSONObject.NULL)
                        .put("charging", state.charging ?: JSONObject.NULL)
                        .put("plugged", state.plugged ?: JSONObject.NULL)
                }
                else -> WmBluetoothRoute.command(args[0] == "bt-route")
            }
            value.put("ok", true).put("action", args[0])
        } catch (_: Throwable) { JSONObject().put("ok", false) }
        println(WmVehicleRoot.PREFIX + result.toString())
        kotlin.system.exitProcess(0)
    }
}
