// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.os.Binder
import android.os.Looper
import android.os.Parcel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
@LooperMode(LooperMode.Mode.PAUSED)
class TirePressureMonitorTest {
    @Test
    fun factoryWithoutListenerSupportStillDisplaysItsAllowedPressureReads() {
        val read = CountDownLatch(12)
        val sample = intArrayOf(98, 99, 98, 103)
        val service = object : Binder() {
            init { attachInterface(null, "com.yftech.carmanager.ICarInfo") }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface("com.yftech.carmanager.ICarInfo")
                when (code) {
                    1 -> {
                        assertNotNull(data.readStrongBinder())
                        assertEquals(1, data.readInt())
                        reply!!.writeNoException()
                        reply.writeInt(0) // OEM read-only stub has no listener implementation.
                    }
                    4, 5 -> {
                        val position = data.readInt()
                        assertTrue(position in 0..3)
                        assertEquals(1, data.readInt())
                        reply!!.writeNoException()
                        reply.writeInt(if (code == 4) sample[position] else 0)
                        read.countDown()
                    }
                    9, 15 -> {
                        val value = if (code == 15) 397 else when (data.readInt()) {
                            0 -> 673943
                            1 -> 13
                            2 -> 7667
                            else -> error("Unexpected odometer type")
                        }
                        reply!!.writeNoException()
                        reply.writeInt(value)
                        read.countDown()
                    }
                    else -> error("Unexpected vehicle transaction: $code")
                }
                return true
            }
        }
        val observed = AtomicReference<List<TirePressureReading>?>()
        val stats = AtomicReference<VehicleReadings?>()
        val monitor = WmTirePressureMonitor(findService = { service },
            renderVehicle = { stats.set(it) }) { observed.set(it) }
        try {
            monitor.start()
            assertTrue("The getters must still be read when subscription is refused", read.await(2, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while ((observed.get() == null || stats.get() == null) && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(5)
            }
            assertEquals(listOf(270, 272, 270, 283), observed.get()?.map { it.kpa })
            assertEquals("--%", stats.get()?.batteryText)
            assertEquals("397 km", stats.get()?.rangeText)
            assertEquals("1.3 km", stats.get()?.tripAText)
            assertEquals("766.7 km", stats.get()?.tripBText)
            assertEquals("67394 km", stats.get()?.totalText)
        } finally {
            monitor.stop()
        }
    }
}
