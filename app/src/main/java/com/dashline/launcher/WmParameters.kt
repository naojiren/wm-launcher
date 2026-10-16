// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.StatFs
import android.os.SystemClock
import org.json.JSONObject
import java.util.Locale

internal data class ParameterCategory(val id: String, val label: String)
internal data class ParameterField(val id: String, val group: String, val method: String,
    val code: Int, val arguments: List<Int>?, val result: String, val label: String, val category: String,
    val nativeCode: Int? = null, val nativeFixedArguments: List<Int> = emptyList())

/** Shipped, closed read contract, checked against both original firmware SDKs. */
internal class WmParameterCatalog(context: Context) {
    val categories: List<ParameterCategory>
    val fields: List<ParameterField>
    init {
        val json = context.assets.open("wm-read-parameters.json").bufferedReader().use { JSONObject(it.readText()) }
        val groups = json.getJSONArray("categories")
        categories = (0 until groups.length()).map { groups.getJSONObject(it).let { row ->
            ParameterCategory(row.getString("id"), row.getString("label")) } }
        val rows = json.getJSONArray("fields")
        fields = (0 until rows.length()).map { rows.getJSONObject(it).let { row ->
            val args = if (row.isNull("args")) null else row.getJSONArray("args").let { values ->
                (0 until values.length()).map { index -> values.getInt(index) } }
            ParameterField(row.getString("id"), row.getString("interface"), row.getString("method"),
                row.getInt("code"), args, row.getString("result"), row.getString("label"), row.getString("category"),
                if (row.isNull("nativeCode")) null else row.getInt("nativeCode"),
                row.getJSONArray("nativeFixedArgs").let { values -> (0 until values.length()).map { i -> values.getInt(i) } })
        } }
        require(fields.map { it.id }.toSet().size == fields.size)
        require(fields.all { it.method.startsWith("get") || it.method.startsWith("is") })
    }
}

internal data class ParameterRead(val value: Any? = null, val problem: String? = null)

/** Never accepts arbitrary transactions, methods or parameter values from an Intent. */
internal class WmParameterBinder(private val catalog: WmParameterCatalog,
    private val find: (String) -> IBinder? = WmDetailBinder::service) {
    fun read(field: ParameterField): ParameterRead {
        require(field in catalog.fields) { "Unreviewed vehicle query" }
        val args = field.arguments ?: return ParameterRead(problem = "读取参数待核验")
        if (Build.VERSION.SDK_INT != 23) return ParameterRead(problem = "此系统不支持原厂接口")
        PROPERTIES[field.method]?.let { key ->
            return try {
                val value = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                    .invoke(null, key) as? String
                when (value?.lowercase(Locale.US)) {
                    "1", "true", "yes", "on", "y" -> ParameterRead(true)
                    "0", "false", "no", "off", "n" -> ParameterRead(false)
                    null, "" -> ParameterRead(problem = "原厂未配置")
                    else -> ParameterRead(problem = "原始配置：$value")
                }
            } catch (_: Exception) { ParameterRead(problem = "读取不可用") }
        }
        // The original native SDK uses distinct codes and sometimes a fixed dummy argument.
        // Its getters were resolved from the read proxies, never inferred from AIDL numbers.
        val native = native(field)
        if (native?.value != null) return native
        val service = SERVICES[field.group] ?: return ParameterRead(problem = "接口不可用")
        return try {
            val remote = find(service) ?: return native ?: ParameterRead(problem = "原厂服务不可用")
            val descriptor = "com.yftech.carmanager.${field.group}"
            if (remote.interfaceDescriptor != descriptor) return ParameterRead(problem = "原厂接口不匹配")
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                args.forEach(data::writeInt)
                if (!remote.transact(field.code, data, reply, 0)) return ParameterRead(problem = "读取不可用")
                reply.readException()
                val value: Any? = when (field.result) {
                    "I" -> if (reply.dataAvail() == 4) reply.readInt() else null
                    "Z" -> if (reply.dataAvail() == 4) when (reply.readInt()) { 0 -> false; 1 -> true; else -> null } else null
                    "Ljava/lang/String;" -> if (reply.dataAvail() in 4..8192) reply.readString()?.takeIf { it.isNotBlank() } else null
                    "[I" -> {
                        val count = if (reply.dataAvail() >= 4) reply.readInt() else -1
                        if (count in 0..2048 && reply.dataAvail() == count * 4) List(count) { reply.readInt() } else null
                    }
                    else -> null
                }
                if (reply.dataAvail() != 0 || value == null) ParameterRead(problem = "返回值不可用") else ParameterRead(value)
            } finally { data.recycle(); reply.recycle() }
        } catch (_: SecurityException) { ParameterRead(problem = "读取权限不足")
        } catch (_: Exception) { ParameterRead(problem = "读取不可用") }
    }
    private fun native(field: ParameterField): ParameterRead? {
        val code = field.nativeCode ?: return null
        val service = NATIVE_SERVICES[field.group] ?: return null
        return try {
            val remote = find(service) ?: return null
            if (remote.interfaceDescriptor != service) return ParameterRead(problem = "原厂接口不匹配")
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(service)
                (field.arguments.orEmpty() + field.nativeFixedArguments).forEach(data::writeInt)
                if (!remote.transact(code, data, reply, 0) || reply.dataAvail() != 4) return ParameterRead(problem = "返回值不可用")
                val n = reply.readInt()
                val value: Any? = if (field.result == "Z" && field.method != "getSunShadeState")
                    when (n) { 0 -> false; 1 -> true; else -> null } else n
                if (value == null) ParameterRead(problem = "无效读数") else ParameterRead(value)
            } finally { data.recycle(); reply.recycle() }
        } catch (_: SecurityException) { ParameterRead(problem = "读取权限不足")
        } catch (_: Exception) { ParameterRead(problem = "读取不可用") }
    }
    companion object {
        val SERVICES = mapOf("ICarInfo" to "com.yftech.CarInfoService", "ICarControl" to "com.yftech.CarControlService",
            "IAirCondition" to "com.yftech.AirConditionService", "ICarSetup" to "com.yftech.CarSetupService")
        val NATIVE_SERVICES = mapOf("ICarControl" to "yfcarcontrol", "IAirCondition" to "yfaircondition", "ICarSetup" to "yfcarsetup")
        val PROPERTIES = mapOf("getExhibitionModeEnable" to "persist.exhibition.enable",
            "getProjectorEnable" to "persist.projector.enable", "getWelcomeModeEnable" to "persist.welcome.mode")
    }
}

internal object WmParameterFormat {
    fun value(field: ParameterField, read: ParameterRead): String {
        read.problem?.let { return it }
        val raw = read.value ?: return "不可用"
        if (raw is Boolean) return if (raw) "开启 / 是" else "关闭 / 否"
        if (raw is String) return raw
        if (raw is List<*>) return if (raw.isEmpty()) "暂无统计数据" else "${raw.size} 项原始数据（单位待核验）\n" + raw.joinToString(" · ")
        val n = raw as? Int ?: return "不可用"
        if (n in listOf(65535, Int.MAX_VALUE, Int.MIN_VALUE)) return "无效读数"
        val type = field.arguments?.firstOrNull()
        // Preserve legitimate negative angles, currents and temperatures.
        val signed = field.method in setOf("getSasAngle", "getSasRotateSpeed", "getYRSLongitAcce", "getTemperature") ||
            (field.method == "getBatteryInfo" && type in listOf(1, 2, 3, 9)) ||
            (field.method == "getMotorInfo" && type in listOf(4, 5))
        if (n < 0 && !signed) return "无效读数"
        if (field.method == "getChargeInfo" && type == 10) return WmDetailFormat.soc(n)
        if (field.method == "getBatteryInfo" && type == 4) return WmDetailFormat.health(n)
        if (field.method == "getRemainDriveDistance") return n.takeIf { it in 0..9999 }?.let { "$it km" } ?: "无效读数"
        if (field.method == "getOdometerInfo" && type in 0..2) return n.takeIf { it in 0..9_999_999 }?.let {
            if (type == 0) "${it / 10} km" else "${it / 10}.${it % 10} km" } ?: "无效读数"
        // ICU SOC returned zero despite a charged traction battery on this firmware.
        if (field.method == "getOdometerInfo") return "$n（含义及单位待核验）"
        if (field.method == "getTPMSInfo" && field.arguments?.getOrNull(1) == 1)
            return TirePressureReading(n, null).kpa?.let { "$it kPa" } ?: "无效读数"
        val enum = when (field.method) {
            "getVehicleGears" -> mapOf(0 to "P", 1 to "N", 2 to "D", 3 to "R")
            "getDriveMode" -> mapOf(1 to "ECO", 2 to "SPORT")
            "getEnergyRecupIntensity" -> mapOf(0 to "关闭", 1 to "中", 2 to "高")
            "getVehicleBrakeState" -> mapOf(0 to "未制动", 1 to "制动")
            "getDoorState" -> mapOf(0 to "关闭", 1 to "打开")
            "getDoorLockState" -> mapOf(0 to "未锁", 1 to "已锁")
            "getVehiclePowertrainMode" -> mapOf(0 to "OFF", 1 to "待机", 2 to "READY", 3 to "紧急关闭")
            "getVehiclePowerMode" -> mapOf(0 to "OFF", 1 to "ACC", 2 to "ON", 3 to "START")
            "getVehiclePowerState" -> mapOf(0 to "关闭", 1 to "本地", 2 to "远程", 3 to "OTA")
            "getVehicleArmState" -> mapOf(0 to "未设防", 1 to "已设防", 2 to "警报", 3 to "部分设防")
            "getCycleMode" -> mapOf(0 to "外循环", 1 to "内循环", 2 to "自动循环")
            "getACWorkMode" -> mapOf(0 to "舒适", 1 to "节能", 2 to "通风")
            "getDisplayMode" -> mapOf(0 to "日间", 1 to "夜间")
            "getEPSMode" -> mapOf(1 to "标准", 2 to "舒适", 3 to "运动")
            "getESCMode" -> mapOf(0 to "关闭", 1 to "普通", 2 to "运动")
            "getCarWindowState" -> mapOf(0 to "完全关闭", 1 to "部分打开", 2 to "完全打开")
            "getSeatHeaterValue" -> mapOf(0 to "关闭", 1 to "1 档", 2 to "2 档", 3 to "3 档", 4 to "自动")
            "getWipingState" -> mapOf(0 to "关闭", 1 to "低速", 2 to "高速", 3 to "点动", 4 to "间歇", 5 to "自动")
            "getChargeInfo" -> if (type in listOf(0, 3, 7)) mapOf(0 to "未充电", 1 to "充电中") else emptyMap()
            else -> emptyMap()
        }
        return enum[n]?.let { "$it（$n）" } ?: "$n（原始值，单位或状态待核验）"
    }
}

/** No write operations, raw CAN access, new resident service or off-page polling. */
internal class WmParametersReader(private val context: Context, private val catalog: WmParameterCatalog,
    private val binder: WmParameterBinder = WmParameterBinder(catalog),
    private val batteryFallback: () -> WmBatteryState = { WmBatteryAccess(context).read() }) {
    fun read(category: String, continueReading: () -> Boolean = { true }): List<DetailRow> {
        require(catalog.categories.any { it.id == category })
        if (category == "connections") return WmDetailsReader(context).connections()
        if (category == "system") return system()
        val rows = if (category == "overview") catalog.fields.filter {
            it.method == "getRemainDriveDistance" || it.method == "getVehicleGears" || it.method == "getVehicleSpeed" ||
                it.method == "getVehiclePowertrainMode" || (it.method == "getChargeInfo" && it.arguments == listOf(10)) ||
                (it.method == "getOdometerInfo" && it.arguments?.firstOrNull() in 0..2) ||
                (it.method == "getTPMSInfo" && it.arguments?.getOrNull(1) == 1)
        }.sortedBy { field -> when (field.method) {
            "getChargeInfo" -> 0
            "getRemainDriveDistance" -> 1
            "getOdometerInfo" -> 2 + (field.arguments?.firstOrNull() ?: 0)
            "getTPMSInfo" -> 5 + (field.arguments?.firstOrNull() ?: 0)
            "getVehicleGears" -> 9
            "getVehiclePowertrainMode" -> 10
            else -> 11
        } } else catalog.fields.filter { it.category == category }
        val result = mutableListOf<DetailRow>()
        val cache = mutableMapOf<ParameterField, ParameterRead>()
        fun get(field: ParameterField) = cache.getOrPut(field) { binder.read(field) }
        for (field in rows) {
            if (!continueReading()) break
            var value = get(field)
            if (field.method == "getChargeInfo" && field.arguments == listOf(10) &&
                (value.value as? Int)?.takeIf { it in 0..1000 } == null && continueReading()) {
                val battery = batteryFallback()
                value = if (battery.tenths != null) ParameterRead(battery.tenths)
                    else ParameterRead(problem = battery.problem ?: value.problem)
            }
            var formatted = WmParameterFormat.value(field, value)
            if (field.method == "getTPMSInfo" && field.arguments?.getOrNull(1) == 1 && value.value is Int) {
                if (!continueReading()) break
                val warningField = catalog.fields.first { it.method == "getTPMSWarnInfo" &&
                    it.arguments == listOf(field.arguments.first(), 1) }
                val warning = get(warningField).value as? Int
                if (warning in listOf(4, 7)) formatted = "传感器读数不可用"
            }
            result += DetailRow(field.label, formatted)
        }
        return result
    }
    private fun system(): List<DetailRow> = buildList {
        add(DetailRow("系统与设备", "", true))
        add(DetailRow("Android", "${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"))
        add(DetailRow("厂商 / 型号", "${Build.MANUFACTURER} / ${Build.MODEL}"))
        add(DetailRow("硬件 / 主板", "${Build.HARDWARE} / ${Build.BOARD}"))
        add(DetailRow("系统版本号", Build.DISPLAY))
        add(DetailRow("安全补丁", Build.VERSION.SECURITY_PATCH.ifBlank { "不可用" }))
        add(DetailRow("CPU 架构", Build.SUPPORTED_ABIS.joinToString(" / ")))
        add(DetailRow("CPU 核心数", Runtime.getRuntime().availableProcessors().toString()))
        val seconds = SystemClock.elapsedRealtime() / 1000
        add(DetailRow("本次开机时长", "${seconds / 3600} 小时 ${(seconds % 3600) / 60} 分钟"))
        add(DetailRow("内存与存储", "", true))
        val memory = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memory)
        fun bytes(n: Long) = String.format(Locale.US, "%.1f GiB", n / 1073741824.0)
        add(DetailRow("总内存", bytes(memory.totalMem)))
        add(DetailRow("可用内存", bytes(memory.availMem)))
        add(DetailRow("低内存状态", if (memory.lowMemory) "是" else "否"))
        val disk = StatFs(context.filesDir.absolutePath)
        add(DetailRow("数据分区总容量", bytes(disk.totalBytes)))
        add(DetailRow("数据分区可用容量", bytes(disk.availableBytes)))
        add(DetailRow("当前桌面版本", BuildConfig.VERSION_NAME))
        add(DetailRow("车辆接口权限", "", true))
        for ((name, title) in listOf("CARINFO" to "车辆信息", "CARCONTROL" to "车身与充电", "AIRCONDITION" to "空调", "CARSETUP" to "车辆配置")) {
            val granted = context.checkSelfPermission("yftech.permission.USE_$name") == android.content.pm.PackageManager.PERMISSION_GRANTED
            add(DetailRow(title, if (granted) "已授权" else "未授权"))
        }
    }
}
