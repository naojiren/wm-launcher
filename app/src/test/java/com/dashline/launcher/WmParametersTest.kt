// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.Intent
import android.os.Binder
import android.os.Parcel
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class WmParametersTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val catalog get() = WmParameterCatalog(context)
    private fun field(name: String, args: List<Int> = emptyList()) = catalog.fields.first { it.method == name && it.arguments == args }
    @Test fun catalogCoversAllFourSdkGroupsAndNeverListsWrites() {
        val fields = catalog.fields
        assertEquals(mapOf("ICarInfo" to 34, "ICarControl" to 32, "IAirCondition" to 20, "ICarSetup" to 36),
            fields.groupBy { it.group }.mapValues { (_, values) -> values.map { it.method }.toSet().size })
        assertEquals(259, fields.size)
        assertEquals(13, catalog.categories.size)
        assertFalse(fields.any { it.method.startsWith("set") || it.method.contains("Reset") || it.method.startsWith("clear", true) })
        assertEquals(17, field("getBatteryInfo", listOf(5)).code)
        assertEquals(4, field("getTPMSInfo", listOf(0,1)).code)
        assertEquals((0..12).toList(), fields.filter { it.method == "getChargeInfo" }.map { it.arguments!!.single() })
        assertEquals("左前车门锁",field("getDoorLockState",listOf(0)).label)
        assertEquals("左前车门",field("getDoorState",listOf(0)).label)
        assertEquals("左前车窗",field("getCarWindowState",listOf(0)).label)
        assertNotEquals(field("getVehicleWarnInfo",listOf(1)).label,field("getVehicleCommunicationState",listOf(1)).label)
    }
    @Test fun rejectsAnAlteredTransactionOrAnUnreviewedParameterBeforeAnyTransport() {
        var calls = 0
        val binder = WmParameterBinder(catalog) { calls++; null }
        for (bad in listOf(field("getVehicleSpeed").copy(code=999), field("getBatteryInfo", listOf(5)).copy(arguments=listOf(999)))) {
            try { binder.read(bad); fail("Unreviewed queries must fail closed") } catch (_: IllegalArgumentException) { }
        }
        val unknown = catalog.fields.first { it.arguments == null }
        assertEquals("读取参数待核验", binder.read(unknown).problem)
        assertEquals(0, calls)
    }
    @Test fun readsJavaScalarBooleanStringAndBoundedArrayContracts() {
        val selected = listOf(field("getSasAngle"), field("getKeyInCarState"), field("getVehicleVIN"), field("getSpeedInfo", listOf(2)))
        var expected = selected.first()
        val server = object : Binder() {
            init { attachInterface(null, WmDetailBinder.INFO_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(expected.code, code); data.enforceInterface(WmDetailBinder.INFO_DESCRIPTOR)
                expected.arguments!!.forEach { assertEquals(it, data.readInt()) }
                assertEquals(0,data.dataAvail()); reply!!.writeNoException()
                when(expected.result) {
                    "I" -> reply.writeInt(-125)
                    "Z" -> reply.writeInt(1)
                    "Ljava/lang/String;" -> reply.writeString("VIN-TEST")
                    "[I" -> reply.writeIntArray(intArrayOf(12,24,36))
                }
                return true
            }
        }
        val reader = WmParameterBinder(catalog) { server }
        val expectedValues = listOf(-125,true,"VIN-TEST",listOf(12,24,36))
        selected.forEachIndexed { i, f -> expected=f; assertEquals(expectedValues[i],reader.read(f).value) }
    }
    @Test fun nativeSocUsesItsOwnDescriptorAndHasNoJavaExceptionHeader() {
        val f=field("getChargeInfo",listOf(10))
        val native=object:Binder() {
            init { attachInterface(null,WmBatteryReader.SERVICE) }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                assertEquals(60,code); data.enforceInterface(WmBatteryReader.SERVICE)
                assertEquals(10,data.readInt()); assertEquals(0,data.dataAvail())
                reply!!.writeInt(980); return true
            }
        }
        assertEquals("98%",WmParameterFormat.value(f,WmParameterBinder(catalog) { native }.read(f)))
    }
    @Test fun nativeAirAndSetupUseAuditedCodesInsteadOfJavaTransactionNumbers() {
        val requests = listOf(field("getTemperature",listOf(0)),field("getAEBEnable"),field("getBackDoorEnabled"))
        val nativeCodes = listOf(12,21,28)
        for ((i,f) in requests.withIndex()) {
            val descriptor=WmParameterBinder.NATIVE_SERVICES.getValue(f.group)
            val server=object:Binder() {
                init { attachInterface(null,descriptor) }
                override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                    assertEquals(nativeCodes[i],code)
                    if (i != 1) assertNotEquals(f.code,code)
                    data.enforceInterface(descriptor)
                    if (i==0 || i==2) assertEquals(0,data.readInt())
                    assertEquals(0,data.dataAvail())
                    reply!!.writeInt(if (i==0) -5 else 1); return true
                }
            }
            val read=WmParameterBinder(catalog) { if (it==descriptor) server else error("Native getter must succeed without Java service") }.read(f)
            assertEquals(if (i==0) -5 else true,read.value)
        }
    }
    @Test fun changingOrLeavingCategoryCancelsRemainingReadBatch() {
        var count=0
        val reader=WmParametersReader(context,catalog,WmParameterBinder(catalog) { count++; null })
        val rows=reader.read("warning") { count<3 }
        assertEquals(3,count); assertEquals(3,rows.size)
    }
    @Test fun missingDeniedAndMalformedDataStayUnavailableRatherThanZero() {
        val f=field("getVehicleSpeed")
        assertNull(WmParameterBinder(catalog) { null }.read(f).value)
        val denied=object:Binder() {
            init { attachInterface(null,WmDetailBinder.INFO_DESCRIPTOR) }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean { throw SecurityException() }
        }
        assertEquals("读取权限不足",WmParameterBinder(catalog) { denied }.read(f).problem)
        val malformed=object:Binder() {
            init { attachInterface(null,WmDetailBinder.INFO_DESCRIPTOR) }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                reply!!.writeNoException(); reply.writeInt(0); reply.writeInt(0); return true
            }
        }
        assertNull(WmParameterBinder(catalog) { malformed }.read(f).value)
        val array=field("getSpeedInfo",listOf(2))
        val oversized=object:Binder() {
            init { attachInterface(null,WmDetailBinder.INFO_DESCRIPTOR) }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                reply!!.writeNoException(); reply.writeInt(Int.MAX_VALUE); return true
            }
        }
        assertNull(WmParameterBinder(catalog) { oversized }.read(array).value)
    }
    @Test fun formattingPreservesSignedValuesAndDoesNotInventUnits() {
        assertTrue(WmParameterFormat.value(field("getSasAngle"),ParameterRead(-20)).startsWith("-20"))
        assertTrue(WmParameterFormat.value(field("getBatteryInfo",listOf(9)),ParameterRead(-15)).startsWith("-15"))
        assertEquals("67394 km",WmParameterFormat.value(field("getOdometerInfo",listOf(0)),ParameterRead(673940)))
        assertEquals("1.3 km",WmParameterFormat.value(field("getOdometerInfo",listOf(1)),ParameterRead(13)))
        assertTrue(WmParameterFormat.value(field("getOdometerInfo",listOf(4)),ParameterRead(0)).contains("待核验"))
        assertEquals("无效读数",WmParameterFormat.value(field("getTPMSInfo",listOf(0,1)),ParameterRead(255)))
        assertTrue(WmParameterFormat.value(field("getBrightness"),ParameterRead(255)).startsWith("255"))
    }
    @Test fun overviewReadsOnlyItsOwnFieldsAndDoesNotFetchEveryCategory() {
        val queried=mutableListOf<String>()
        val reader=WmParametersReader(context,catalog,WmParameterBinder(catalog) { queried+=it; null })
        val overview=reader.read("overview")
        assertEquals(12,overview.size)
        assertEquals("动力电池电量",overview.first().label)
        assertTrue(queried.all { it in listOf(WmDetailBinder.INFO_SERVICE,WmBatteryReader.SERVICE,"com.yftech.CarControlService") })
        assertFalse(overview.any { it.label.contains("AEB") || it.label.contains("气囊") })
    }
    @Test fun pageRemembersCategoryAndStopsItsPollWhenPaused() {
        val activity=Robolectric.buildActivity(ParametersActivity::class.java).create(android.os.Bundle().apply { putString("category","tires") }).start().resume()
        val a=activity.get()
        val selected=ParametersActivity::class.java.getDeclaredField("selected").apply { isAccessible=true }
        assertEquals("tires",selected.get(a))
        val poll=ParametersActivity::class.java.getDeclaredField("poll").apply { isAccessible=true }
        assertNotNull(poll.get(a)); activity.pause(); assertNull(poll.get(a))
        activity.stop().destroy()
    }
    @Test fun homeTimeAndDateOpenTheSameDetailsPage() {
        context.getSharedPreferences("radio_launcher",0).edit().putBoolean("setup_done",true).commit()
        val controller=Robolectric.buildActivity(HomeActivity::class.java).create()
        val a=controller.get()
        for (id in listOf(R.id.bigClock,R.id.bigDate,R.id.analogClock,R.id.clockSlot)) {
            assertTrue(a.findViewById<View>(id).performClick())
            assertEquals(ParametersActivity::class.java.name,shadowOf(a).nextStartedActivity.component!!.className)
        }
        controller.destroy()
    }
}
