// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.ComponentName
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class WmHomePersistenceTest {
    private val target = ComponentName("com.wmhome.launcher", "com.dashline.launcher.HomeActivity")
    private fun xml(section: String = "persistent-preferred-activities", name: String = target.flattenToString(),
                    always: String = "true", extra: String = "", home: String = "android.intent.category.HOME") =
        """<package-restrictions><$section><item name="$name" always="$always"><filter>
        <action name="android.intent.action.MAIN"/><cat name="$home"/>
        <cat name="android.intent.category.DEFAULT"/>$extra</filter></item></$section></package-restrictions>"""
    @Test fun savedPersistentHomeIsDistinctFromNormalPreference() {
        assertTrue(WmHomeStore.contains(xml().reader(), target, true))
        assertFalse(WmHomeStore.contains(xml("preferred-activities").reader(), target, true))
        assertTrue(WmHomeStore.contains(xml("preferred-activities").reader(), target, false))
    }
    @Test fun wrongHomeComponentAndNonHomeFiltersDoNotCountAsSaved() {
        assertFalse(WmHomeStore.contains(xml(name="com.wm.launcher/.Launcher").reader(), target, true))
        assertFalse(WmHomeStore.contains(xml(home="android.intent.category.LAUNCHER").reader(), target, true))
        assertFalse(WmHomeStore.contains(xml(extra="<scheme name=\"https\"/>").reader(), target, true))
        assertFalse(WmHomeStore.contains(xml("preferred-activities", always="false").reader(), target, false))
    }
    @Test fun matchingComponentInUnrelatedSectionIsIgnored() {
        assertFalse(WmHomeStore.contains(xml("packages").reader(), target, true))
    }
    @Test fun manualDefaultIsUpgradedToPersistentEvenWhenHomeAlreadyResolves() {
        var pinned = false; var writes = 0
        repeat(2) {
            assertTrue(WmHomeRepair.ensure({ true }, { pinned = true; writes++ }, { true },
                { error("card already ready") }, { !pinned }).getBoolean("complete"))
        }
        assertEquals(1, writes)
    }
    @Test fun startupRetriesAfterDaemonFailureAndStopsImmediatelyWhenVerified() {
        var attempts = 0; val pauses = mutableListOf<Long>()
        val result = WmHomeRetries.run({
            attempts++
            if (attempts < 3) throw IllegalStateException("local daemon booting")
            JSONObject().put("complete", true)
        }, { pauses.add(it) })
        assertTrue(result.getOrThrow().getBoolean("complete"))
        assertEquals(3, attempts); assertEquals(listOf(2000L, 5000L), pauses)
    }
    @Test fun partialRepairRetriesAndPermanentFailureHasFiniteBudget() {
        var attempts = 0; val pauses = mutableListOf<Long>()
        val result = WmHomeRetries.run({ attempts++; JSONObject().put("complete", false)
            .put("homeEnabled", true) }, { pauses.add(it) })
        assertFalse(result.getOrThrow().getBoolean("complete"))
        assertEquals(5, attempts); assertEquals(4, pauses.size)
        assertTrue(result.getOrThrow().getBoolean("homeEnabled"))
    }
    @Test fun successfulStartupNeverWaitsOrReplaysMutation() {
        var attempts = 0
        assertTrue(WmHomeRetries.run({ attempts++; JSONObject().put("complete", true) },
            { error("success must not sleep") }).isSuccess)
        assertEquals(1, attempts)
    }
}
