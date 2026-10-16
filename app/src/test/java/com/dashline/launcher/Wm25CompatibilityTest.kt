// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.os.Binder
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class Wm25CompatibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Test fun slowChargingOverridesTheInconsistentGeneralChargeFlag() {
        val state = WmBatteryState.from { mapOf(10 to 330, 0 to 0, 3 to 0, 7 to 1, 2 to 0, 6 to 1)[it] }
        assertEquals(330, state.tenths); assertEquals(true, state.charging); assertEquals(true, state.plugged)
        val pluggedOnly = WmBatteryState.from { mapOf(10 to 400, 0 to 0, 3 to 0, 7 to 0, 2 to 0, 6 to 1)[it] }
        assertEquals(false, pluggedOnly.charging); assertEquals(true, pluggedOnly.plugged)
        assertNull(WmBatteryState.from { null }.charging)
        assertNull(WmBatteryState.from { 65535 }.tenths)
    }
    @Test fun ordinaryInstallUsesTheReadOnlyFallbackWhenNativePermissionIsDenied() {
        var calls = 0
        val reader = WmBatteryAccess(context, direct = { throw SecurityException("denied") }, fallback = {
            calls++; "ignored\nWM_VEHICLE_RESULT={\"ok\":true,\"action\":\"battery\",\"soc\":330,\"charging\":true,\"plugged\":true}\n"
        })
        val result = reader.read()
        assertEquals(1, calls); assertEquals(330, result.tenths); assertEquals(true, result.charging)
        assertNull(result.problem)
    }
    @Test fun grantedPermissionUsesNoRootOrAdbAndZeroRemainsAValidSoc() {
        for (soc in listOf(0, 1000)) {
            val reader = WmBatteryAccess(context, direct = { WmBatteryState(soc, false) }, fallback = { error("must not elevate") })
            assertEquals(soc, reader.read().tenths)
        }
    }
    @Test fun missingAdbAndMalformedSocNeverFabricateAHeadUnitBatteryPercentage() {
        val denied = WmBatteryAccess(context, direct = { WmBatteryState() }, fallback = { error("ADB unavailable") }).read()
        assertNull(denied.tenths); assertTrue(denied.problem!!.contains("ADB"))
        for (raw in listOf("1001", "-1", "65535", "12.3", "\"100\"", "null")) {
            val result = WmBatteryAccess.decode("WM_VEHICLE_RESULT={\"ok\":true,\"action\":\"battery\",\"soc\":$raw}")
            assertNull(raw, result.tenths)
        }
    }
    private fun bluetooth(state: Int, writes: MutableList<Int>) = object : Binder() {
        init { attachInterface(null, WmBluetoothRoute.DESCRIPTOR) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(WmBluetoothRoute.DESCRIPTOR)
            when(code) {
                7 -> { assertEquals(49, data.readInt()); reply!!.writeNoException(); reply.writeInt(state) }
                11 -> { writes += data.readInt(); assertNull(data.readString()); reply!!.writeNoException(); reply.writeInt(1) }
                else -> error("unexpected audio transaction")
            }
            assertEquals(0, data.dataAvail()); return true
        }
    }
    @Test fun bluetoothUsesOnlyTheAuditedMediaProfileAndFactoryPlayRoute() {
        val writes = mutableListOf<Int>()
        assertTrue(WmBluetoothRoute.route(bluetooth(2, writes)))
        assertEquals(listOf(134), writes)
        for (state in listOf(0, 1, 3, -1)) {
            writes.clear(); assertFalse(WmBluetoothRoute.route(bluetooth(state, writes))); assertTrue(writes.isEmpty())
        }
    }
    @Test fun bluetoothRoutesOncePerConnectionAndRetriesAreBounded() {
        val policy = WmBluetoothConnectionPolicy()
        assertFalse(policy.observe(1)); assertFalse(policy.observe(null))
        repeat(3) { assertTrue(policy.observe(2)); policy.attempted(false) }
        assertFalse(policy.observe(2)); assertFalse(policy.observe(0))
        assertTrue(policy.observe(2)); policy.attempted(true)
        repeat(8) { assertFalse(policy.observe(2)) }
        assertFalse(policy.observe(3)); assertTrue(policy.observe(2))
    }
    @Test fun allThirteenMenuItemsWrapAndStayInsideBothVehicleScreenWidths() {
        val catalog = WmParameterCatalog(context)
        for (width in listOf(1080, 1920)) {
            val menu = ParameterMenuLayout(context)
            for (category in catalog.categories) menu.addView(TextView(context).apply {
                text = category.label; textSize = 26f; minHeight = 68
                setPadding(22, 10, 22, 10)
            }, ViewGroup.LayoutParams(-2, -2))
            menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.AT_MOST))
            menu.layout(0, 0, width, menu.measuredHeight)
            val children = (0 until menu.childCount).map { menu.getChildAt(it) }
            assertEquals(13, children.size)
            assertTrue(children.all { it.left >= 0 && it.right <= width && it.bottom <= menu.height })
            assertTrue(children.map { it.top }.distinct().size in 1..3)
            assertTrue(menu.height < 400)
        }
    }
}
