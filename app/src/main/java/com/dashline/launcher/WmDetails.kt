// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import java.util.Locale

internal data class DetailRow(val label: String, val value: String, val heading: Boolean = false)

/** OEM SDK registration/access enums, not Android's phone/SIM status. */
internal data class WmLteStatus(val signal: Int? = null, val registration: Int? = null, val access: Int? = null) {
    val connected: Boolean get() = registration == 1 || registration == 5
    val registrationText: String get() = when (registration) {
        0 -> "未注册"; 1 -> "已注册"; 2 -> "搜索网络中"; 3 -> "注册被拒绝"
        4 -> "未知"; 5 -> "漫游"; else -> "不可用"
    }
    val accessText: String get() = when (access) {
        0 -> "未知"; 1 -> "CDMA"; 2 -> "GSM"; 3 -> "WCDMA"; 4 -> "TD-SCDMA"
        5 -> "HDR"; 6 -> "LTE"; else -> "不可用"
    }
    // The vendor's strength scale has not been verified; don't invent bars/dBm.
    val signalText: String get() = when (signal) {
        null -> "不可用"; 99, 255, -1 -> "未知"
        else -> "${signal}（等级单位待核验）"
    }
}

internal object WmDetailFormat {
    const val UNAVAILABLE = "不可用"
    fun flag(value: Int?, yes: String, no: String): String = when (value) {
        0 -> no; 1 -> yes; else -> UNAVAILABLE
    }
    fun pending(value: Int?): String = value?.takeIf { it >= 0 && it != 65535 && it != 255 }
        ?.let { "$it（单位待核验）" } ?: UNAVAILABLE
    fun health(value: Int?): String = value?.takeIf { it in 0..1000 }
        ?.let { String.format(Locale.ROOT, "%.1f%%", it / 10.0) } ?: UNAVAILABLE
    fun soc(value: Int?): String = value?.takeIf { it in 0..1000 }
        ?.let { "${it / 10}%" } ?: UNAVAILABLE
    fun chargeOnly(charging: Int?, value: Int?): String = when (charging) {
        0 -> "未充电"; 1 -> pending(value); else -> UNAVAILABLE
    }
}

/** Closed list of read-only transactions, audited against this Android 6 firmware. */
internal object WmDetailBinder {
    const val INFO_SERVICE = "com.yftech.CarInfoService"
    const val INFO_DESCRIPTOR = "com.yftech.carmanager.ICarInfo"
    fun service(name: String): IBinder? = if (Build.VERSION.SDK_INT != 23) null else try {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    } catch (_: Exception) { null }

    fun info(binder: IBinder?, code: Int, type: Int? = null): Int? {
        require(when (code) {
            11, 21, 22 -> type == null
            12 -> type in 0..3
            17 -> type in 0..9 || type == 20
            else -> false
        })
        return try {
            if (binder == null || binder.interfaceDescriptor != INFO_DESCRIPTOR) return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(INFO_DESCRIPTOR)
                if (type != null) data.writeInt(type)
                if (!binder.transact(code, data, reply, 0)) return null
                reply.readException()
                if (reply.dataAvail() != 4) null else reply.readInt()
                    .takeIf { it >= 0 && it != 65535 && it != Int.MAX_VALUE }
            } finally { reply.recycle(); data.recycle() }
        } catch (_: Exception) { null }
    }
}

/** Only the selected, public status tables. No account, VIN, insert/update/delete. */
internal class WmDetailsReader(context: Context) {
    private val app = context.applicationContext
    private val tables = mapOf(
        "lte_info" to listOf("signal", "regstatus", "access"),
        "gps_info" to listOf("status", "inview", "inuse", "longitude", "latitude", "altitude"),
        "ble_info" to listOf("status"),
        "tbox_status" to listOf("connstatus"),
        "version_info" to listOf("ble", "ecu", "hw", "mcu", "rte", "mpu", "supplier")
    )
    private fun table(name: String): Map<String, String?> {
        val columns = requireNotNull(tables[name])
        if (Build.VERSION.SDK_INT != 23) return emptyMap()
        return try {
            app.contentResolver.query(Uri.parse("content://com.yftech.tboxprovider.TboxProvider/data/$name"),
                columns.toTypedArray(), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) emptyMap() else columns.associateWith { column ->
                    val index = cursor.getColumnIndex(column)
                    if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
                }
            } ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }
    fun lte(): WmLteStatus {
        val row = table("lte_info")
        return WmLteStatus(row["signal"]?.toIntOrNull(), row["regstatus"]?.toIntOrNull(), row["access"]?.toIntOrNull())
    }

    fun battery(): List<DetailRow> {
        val info = WmDetailBinder.service(WmDetailBinder.INFO_SERVICE)
        val control = WmDetailBinder.service(WmBatteryReader.SERVICE)
        fun charge(type: Int): Int? = try { WmBatteryReader.chargeInfo(control, type) } catch (_: Exception) { null }
        val state = WmBatteryAccess(app).read()
        val charging = state.charging?.let { if (it) 1 else 0 }
        fun battery(type: Int) = WmDetailBinder.info(info, 17, type)
        fun flag(type: Int, yes: String, no: String) = WmDetailFormat.flag(charge(type), yes, no)
        fun field(label: String, value: Int?) = DetailRow(label, WmDetailFormat.pending(value))
        return buildList {
            add(DetailRow("动力电池", "", true))
            add(DetailRow("当前电量", WmDetailFormat.soc(state.tenths)))
            state.problem?.let { add(DetailRow("电量读取", it)) }
            add(DetailRow("电池健康度", WmDetailFormat.health(battery(4))))
            add(DetailRow("能耗与本次行程", "", true))
            add(field("瞬时能耗", WmDetailBinder.info(info, 11)))
            for ((type, label) in listOf(0 to "平均能耗", 1 to "近 10 km 平均", 2 to "近 25 km 平均", 3 to "近 50 km 平均"))
                add(field(label, WmDetailBinder.info(info, 12, type)))
            add(field("本次行驶距离", WmDetailBinder.info(info, 21)))
            add(field("本次行驶时间", WmDetailBinder.info(info, 22)))
            add(DetailRow("电池温度", "", true))
            add(field("平均温度", battery(1)))
            add(field("最高温度", battery(2)))
            add(field("最低温度", battery(3)))
            add(DetailRow("电池参数", "", true))
            add(field("高压电池电压", battery(5)))
            add(field("高压电池电流", battery(9)))
            add(field("绝缘电阻", battery(6)))
            add(DetailRow("燃油加热", WmDetailFormat.flag(battery(7), "开启", "关闭")))
            add(DetailRow("PTC 加热", WmDetailFormat.flag(battery(8), "开启", "关闭")))
            add(DetailRow("高压电池断开状态", WmDetailFormat.flag(battery(20), "断开", "连接")))
            add(DetailRow("充电", "", true))
            add(DetailRow("充电状态", WmDetailFormat.flag(charging, "充电中", "未充电")))
            add(DetailRow("快充接口", flag(1, "启用", "未启用")))
            add(DetailRow("快充插头", flag(2, "已连接", "未连接")))
            add(DetailRow("快充状态", flag(3, "充电中", "未充电")))
            add(DetailRow("快充完成", flag(4, "已完成", "未完成")))
            add(DetailRow("慢充接口", flag(5, "启用", "未启用")))
            add(DetailRow("慢充插头", flag(6, "已连接", "未连接")))
            val slow = charge(7)
            add(DetailRow("慢充状态", WmDetailFormat.flag(slow, "充电中", "未充电")))
            add(DetailRow("慢充电流", WmDetailFormat.chargeOnly(slow, charge(8))))
            add(DetailRow("慢充完成", flag(9, "已完成", "未完成")))
            add(DetailRow("预计充电剩余时间", WmDetailFormat.chargeOnly(charging, charge(11))))
            add(DetailRow("充电许可", flag(12, "允许", "未允许")))
        }
    }

    @Suppress("DEPRECATION")
    fun connections(): List<DetailRow> = buildList {
        val status = lte()
        add(DetailRow("车联网 LTE", "", true))
        add(DetailRow("网络注册", status.registrationText))
        add(DetailRow("接入网络", status.accessText))
        add(DetailRow("信号", status.signalText))
        val tbox = table("tbox_status")
        add(DetailRow("TBOX 连接", WmDetailFormat.flag(tbox["connstatus"]?.toIntOrNull(), "已连接", "未连接")))
        val gps = table("gps_info")
        val fix = gps["status"]?.toIntOrNull()
        add(DetailRow("GPS", "", true))
        add(DetailRow("定位状态", when (fix) { 0 -> "未定位"; 1 -> "二维定位"; 2 -> "三维定位"; else -> "不可用" }))
        fun satellite(name: String) = gps[name]?.toIntOrNull()?.takeIf { it in 0..200 }?.toString() ?: "不可用"
        add(DetailRow("可见卫星", satellite("inview")))
        add(DetailRow("使用卫星", satellite("inuse")))
        for ((key, label) in listOf("longitude" to "经度", "latitude" to "纬度", "altitude" to "海拔")) {
            val value = if (fix == 0) "未定位" else if (fix == 1 || fix == 2)
                gps[key]?.let { "$it（单位待核验）" } ?: "不可用" else "不可用"
            add(DetailRow(label, value))
        }
        add(DetailRow("TBOX 蓝牙与版本", "", true))
        add(DetailRow("TBOX 蓝牙", WmDetailFormat.flag(table("ble_info")["status"]?.toIntOrNull(), "已连接", "未连接")))
        val version = table("version_info")
        for ((key, label) in listOf("ble" to "蓝牙版本", "ecu" to "ECU 版本", "hw" to "硬件版本", "mcu" to "MCU 版本", "rte" to "通信模块版本", "mpu" to "MPU 版本", "supplier" to "供应商版本"))
            add(DetailRow(label, version[key]?.takeIf { it.isNotBlank() } ?: "不可用"))

        add(DetailRow("系统网络与 Wi-Fi", "", true))
        val active = try { (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).activeNetworkInfo } catch (_: Exception) { null }
        add(DetailRow("系统网络", if (active?.isConnected == true) "${active.typeName} 已连接" else "未连接"))
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enabled = try { wifi?.isWifiEnabled } catch (_: Exception) { null }
        add(DetailRow("Wi-Fi", when (enabled) { true -> "已开启"; false -> "已关闭"; else -> "不可用" }))
        val connection = try { if (enabled == true) wifi?.connectionInfo else null } catch (_: Exception) { null }
        val wifiConnected = connection != null && connection.networkId >= 0
        add(DetailRow("Wi-Fi 名称", if (wifiConnected) connection?.ssid?.trim('"')?.takeIf { it != "<unknown ssid>" } ?: "不可用" else "未连接"))
        add(DetailRow("Wi-Fi 信号", if (wifiConnected) "${connection!!.rssi} dBm" else "未连接"))
        add(DetailRow("连接速率", if (wifiConnected && connection!!.linkSpeed >= 0) "${connection.linkSpeed} Mbps" else "不可用"))
        add(DetailRow("系统蓝牙", "", true))
        val bluetooth = try { BluetoothAdapter.getDefaultAdapter()?.state } catch (_: Exception) { null }
        add(DetailRow("蓝牙状态", when (bluetooth) {
            BluetoothAdapter.STATE_ON -> "已开启"; BluetoothAdapter.STATE_OFF -> "已关闭"
            BluetoothAdapter.STATE_TURNING_ON -> "正在开启"; BluetoothAdapter.STATE_TURNING_OFF -> "正在关闭"
            else -> "不可用"
        }))
        add(DetailRow("声音与屏幕", "", true))
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        add(DetailRow("媒体音量", "${audio.getStreamVolume(AudioManager.STREAM_MUSIC)} / ${audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}"))
        add(DetailRow("静音模式", when (audio.ringerMode) { AudioManager.RINGER_MODE_NORMAL -> "正常"; AudioManager.RINGER_MODE_SILENT -> "静音"; else -> "振动" }))
        val brightness = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, -1)
        add(DetailRow("屏幕亮度", if (brightness in 0..255) "${(brightness * 100 + 127) / 255}%" else "不可用"))
        val metrics = DisplayMetrics()
        (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        add(DetailRow("屏幕分辨率", "${metrics.widthPixels} × ${metrics.heightPixels}"))
        add(DetailRow("屏幕密度", "${metrics.densityDpi} dpi"))
        add(DetailRow("系统版本", "Android ${Build.VERSION.RELEASE}"))
        add(DetailRow("安装的应用", "", true))
        val installed = app.packageManager.getInstalledApplications(0)
        val factory = installed.count { it.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0 }
        add(DetailRow("第三方应用", "${installed.size - factory} 个"))
        add(DetailRow("系统应用", "$factory 个"))
        add(DetailRow("全部应用", "${installed.size} 个"))
    }
}

/** All work runs off the UI thread, only while its owning window is visible. */
internal class WmForegroundPoll<T>(private val name: String, private val period: Long,
    private val read: () -> T, private val render: (T) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    fun start() {
        if (session != null) return
        val current = Session()
        session = current
        current.worker.post(current.poll)
    }
    fun stop() {
        val old = session ?: return
        session = null
        old.active = false
        old.worker.removeCallbacksAndMessages(null)
        old.worker.post { old.thread.quitSafely() }
    }
    private inner class Session {
        val thread = HandlerThread(name).apply { start() }
        val worker = Handler(thread.looper)
        @Volatile var active = true
        val poll = object : Runnable {
            override fun run() {
                if (!active) return
                try {
                    val value = read()
                    main.post { if (active && session === this@Session) render(value) }
                } catch (error: Exception) {
                    android.util.Log.w("WM-Details", "Status read unavailable: ${error.javaClass.simpleName}")
                }
                if (active) worker.postDelayed(this, period)
            }
        }
    }
}
