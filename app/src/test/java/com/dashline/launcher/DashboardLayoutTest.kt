// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import com.dashline.launcher.databinding.ActivityHomeBinding
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class DashboardLayoutTest {
    private fun yInRoot(view: View): Int {
        var y = 0
        var current: View? = view
        while (current != null) {
            y += current.top
            current = current.parent as? View
        }
        return y
    }
    private fun dashboard(width: Int, height: Int): ActivityHomeBinding {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_RadioLauncher)
        val home = ActivityHomeBinding.inflate(LayoutInflater.from(context))
        home.bigClock.text = "23:59"
        home.bigDate.text = "10月10日 星期六"
        home.tirePressure.pressureTitle.text = "胎压 · kPa"
        home.tirePressure.frontLeft.text = "270"
        home.tirePressure.frontRight.text = "272"
        home.tirePressure.rearLeft.text = "270"
        home.tirePressure.rearRight.text = "283"
        home.vehicleStats.batteryValue.text = "99%"
        home.vehicleStats.rangeValue.setRanges("397 km", "277.2 km")
        home.tripStats.tripAValue.text = "1.3 km"
        home.tripStats.tripBValue.text = "766.7 km"
        home.tripStats.totalValue.text = "67394 km"
        home.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        home.root.layout(0, 0, width, height)
        return home
    }

    @Test @Config(qualifiers = "w1080dp-h1920dp-port-mdpi")
    fun largePortraitKeepsFourWheelsLeftOfRightAlignedClockAndLargerControls() {
        val home = dashboard(1080, 1920)
        assertEquals(56, home.homeButton.width)
        assertTrue(home.topBar.height >= 76)
        assertTrue(home.shortcutAndroidAuto.height >= 80)
        assertTrue(home.shortcutCarPlay.height >= 80)
        assertTrue(home.tirePressure.root.right < (home.clockSlot.parent as View).left)
        assertTrue(home.bigClock.gravity and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT)
        assertTrue(yInRoot(home.tirePressure.frontLeft) < yInRoot(home.tirePressure.rearLeft))
        // Visual themes may enlarge the clock; enforce readability and the
        // column boundaries below instead of locking the previous font size.
        assertTrue(home.bigClock.textSize >= 76f)
        assertTrue(home.bigDate.textSize >= 22f)
        assertEquals(50, home.clockSlot.paddingRight)
        assertEquals(-20f, home.clockSlot.translationX, 0.1f)
        assertEquals(View.GONE, home.tirePressure.pressureStatus.visibility)
        assertClockFits(home)
        val tile = LayoutInflater.from(home.root.context).inflate(R.layout.item_app, null, false)
        tile.measure(View.MeasureSpec.makeMeasureSpec(1008 / 3, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        assertEquals(tile.measuredHeight + 30, AppGrid.tileHeight(home.root.context, 1008))
        assertEquals(2, (home.vehicleStats.root as android.view.ViewGroup).childCount)
        assertEquals(132, home.vehicleStats.batteryValue.width)
        assertEquals(64, home.vehicleStats.batteryValue.height)
        assertEquals(78, home.vehicleStats.batteryValue.left)
        assertEquals(88, home.vehicleStats.root.paddingEnd)
        assertEquals(home.vehicleStats.batteryValue.right + 12, home.vehicleStats.rangeValue.left)
        assertEquals(Gravity.LEFT, home.vehicleStats.rangeValue.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertRangesFit(home)
        assertEquals(1, home.vehicleStats.rangeValue.maxLines)
        assertTrue(home.mediaTitle.textSize >= 34f)
        assertTrue(home.mediaArtist.textSize >= 28f)
        home.mediaControls.visibility = View.VISIBLE
        home.root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
        home.root.layout(0, 0, 1080, 1920)
        assertEquals(112, home.btnPlayPause.width)
        assertEquals(96, home.btnNext.width)
        assertEquals(96, home.btnPrev.width)
        assertTrue(home.tirePressure.root.right <= home.tripStats.root.left)
        assertTrue(home.tripStats.root.right <= (home.clockSlot.parent as View).left)
        assertTrue(yInRoot(home.tripStats.tripAValue) < yInRoot(home.tripStats.tripBValue))
        assertTrue(yInRoot(home.tripStats.tripBValue) < yInRoot(home.tripStats.totalValue))
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-mdpi")
    fun narrowPortraitKeepsVehicleValuesAndThreeDashboardColumnsInsideTheScreen() {
        val home = dashboard(360, 640)
        assertTrue(home.clockSlot.width > 0)
        assertTrue(home.tirePressure.root.width > 0)
        assertTrue(home.bigClock.paint.measureText(home.bigClock.text.toString()) <= home.bigClock.width)
        assertTrue(home.tripStats.root.width > 0)
        for (value in listOf(home.vehicleStats.batteryValue,
                home.tripStats.tripAValue, home.tripStats.tripBValue, home.tripStats.totalValue)) {
            assertTrue(value.paint.measureText(value.text.toString()) <= value.width)
        }
        assertRangesFit(home)
        assertClockFits(home)
        assertTrue(home.shortcutAndroidAuto.height >= 64)
    }

    @Test @Config(qualifiers = "w800dp-h480dp-land-mdpi")
    fun landscapeStillShowsBothTireAndClockColumns() {
        val home = dashboard(800, 480)
        assertTrue(home.tirePressure.root.width > 0)
        assertTrue(home.clockSlot.width > 0)
        assertTrue(home.tripStats.root.width > 0)
        assertEquals(0, home.clockSlot.paddingRight)
        assertTrue(home.tirePressure.root.right < (home.clockSlot.parent as View).left)
        assertTrue(home.shortcutAndroidAuto.bottom <= home.clockPanel.height)
        assertRangesFit(home)
        assertClockFits(home)
    }

    @Test @Config(qualifiers = "w720dp-h1280dp-port-hdpi")
    fun groupMovesLeftAnother30PhysicalPixelsAtHigherDensityWithoutChangingAvailableWidth() {
        val home = dashboard(1080, 1920)
        val density = home.root.resources.displayMetrics.density
        assertEquals((8 * density).toInt() + 70, home.vehicleStats.batteryValue.left)
        assertEquals((8 * density).toInt() + 80, home.vehicleStats.root.paddingEnd)
        assertEquals(home.vehicleStats.batteryValue.right + (12 * density).toInt(), home.vehicleStats.rangeValue.left)
        assertRangesFit(home)
        assertEquals(-20f, home.clockSlot.translationX, 0.1f)
        assertClockFits(home)
    }

    private fun assertClockFits(home: ActivityHomeBinding) {
        assertTrue(home.clockSlot.translationX in -20f..0f)
        for (value in listOf(home.bigClock, home.bigDate)) {
            val layout = value.layout ?: continue
            for (line in 0 until layout.lineCount) {
                assertTrue(value.left + value.compoundPaddingLeft + layout.getLineLeft(line) +
                    home.clockSlot.translationX >= -1f)
            }
            assertTrue(layout.height <= value.height - value.compoundPaddingTop - value.compoundPaddingBottom)
        }
    }

    private fun assertRangesFit(home: ActivityHomeBinding) {
        val ranges = home.vehicleStats.rangeValue
        assertTrue(ranges.text.contains("系统预估 397 km"))
        assertTrue(ranges.text.contains("实际预估里程 277.2 km"))
        for (line in ranges.text.split('\n')) {
            assertTrue(ranges.paint.measureText(line) <= ranges.width + 1f)
        }
        assertNotNull(ranges.layout)
        assertTrue(ranges.layout.height <= ranges.height)
        assertTrue(ranges.top >= 0)
        assertTrue(ranges.bottom <= home.vehicleStats.root.height)
    }

    @Test
    fun pressureConversionMatchesCapturedFactoryValuesAndHidesInvalidOrFailedSensors() {
        assertEquals(270, TirePressureReading(98, 0).kpa)
        assertEquals(272, TirePressureReading(99, 0).kpa)
        assertEquals(283, TirePressureReading(103, 0).kpa)
        for (raw in listOf(null, -1, 0, 255, 65535)) assertNull(TirePressureReading(raw, 0).kpa)
        assertNull(TirePressureReading(98, 4).kpa)
        assertNull(TirePressureReading(98, 7).kpa)
        assertTrue(TirePressureReading(98, 1).hasWarning)
        assertFalse(TirePressureReading(98, 0).hasWarning)
    }
}
