// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.app.Instrumentation
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Real API 23 UI navigation and foreground-only reading checks on the disposable emulator. */
class WmParametersRuntime:Instrumentation() {
    private var args=Bundle()
    override fun onCreate(arguments:Bundle?) { args=arguments?:Bundle();start() }
    override fun onStart() {
        val checks=mutableListOf<String>()
        try {
            require(Build.VERSION.SDK_INT==23 && Build.MODEL!="CQ1301" && Build.FINGERPRINT.contains("generic"))
            fun check(name:String,result:Boolean) { require(result) { name };checks+="PASS: $name" }
            val context=targetContext
            context.getSharedPreferences("radio_launcher",0).edit().putBoolean("setup_done",true)
                .putString("wm_orientation",args.getString("orientation","portrait")).commit()
            val home=startActivitySync(Intent(context,HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val monitor=addMonitor(ParametersActivity::class.java.name,null,false)
            runOnMainSync { home.findViewById<View>(R.id.bigClock).performClick() }
            val page=waitForMonitorWithTimeout(monitor,5000) as? ParametersActivity ?: error("Clock did not open parameter page")
            removeMonitor(monitor)
            waitForIdleSync()
            check("requested screen orientation is active",page.resources.configuration.orientation ==
                if(args.getString("orientation")=="landscape") android.content.res.Configuration.ORIENTATION_LANDSCAPE
                else android.content.res.Configuration.ORIENTATION_PORTRAIT)
            fun texts(view:View):List<String> = if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                else if(view is TextView)listOf(view.text.toString()) else emptyList()
            fun await(text:String) {
                var found=false
                repeat(50) {
                    runOnMainSync { found=texts(page.window.decorView).any { it.contains(text) } }
                    if(!found)Thread.sleep(100)
                }
                check("render $text",found)
            }
            check("clock opens local page",page is ParametersActivity)
            await("98%");await("67394 km");await("1.3 km");await("275 kPa")
            val tabField=ParametersActivity::class.java.getDeclaredField("tabs").apply { isAccessible=true }
            runOnMainSync {
                val tabs = tabField.get(page) as ViewGroup
                check("all categories visible without horizontal scrolling", tabs.childCount == 13 &&
                    (0 until tabs.childCount).all { i ->
                        val child = tabs.getChildAt(i)
                        child.left >= 0 && child.right <= tabs.width && child.bottom <= tabs.height && child.height > 0
                    })
                check("categories wrap into at most three rows", (0 until tabs.childCount)
                    .map { tabs.getChildAt(it).top }.distinct().size in 1..3)
                check("menu text increased by one point", (tabs.getChildAt(0) as TextView).textSize /
                    page.resources.displayMetrics.scaledDensity >= 26f)
            }
            fun category(id:String) {
                runOnMainSync {
                    val tabs=tabField.get(page) as ViewGroup
                    (0 until tabs.childCount).map { tabs.getChildAt(it) }.first { it.tag==id }.performClick()
                }
            }
            category("air");await("-5（原始值")
            category("setup");await("开启 / 是")
            category("identity");await("VIN-EMU-TEST")
            category("drive");await("12 · 24 · 36")
            category("system");await("本次开机时长")
            runOnMainSync { page.findViewById<View>(R.id.navBack).performClick() }
            waitForIdleSync()
            check("back returns to home",page.isFinishing)
            val polling=ParametersActivity::class.java.getDeclaredField("poll").apply { isAccessible=true }
            check("page polling is stopped after exit",polling.get(page)==null)
            val settings = startActivitySync(Intent(context,SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            val audioSwitch = settings.findViewById<android.widget.CompoundButton>(R.id.wmBluetoothAudioSwitch)
            runOnMainSync { audioSwitch.isChecked = false }
            Thread.sleep(400)
            check("automatic Bluetooth audio can be disabled", !context.getSharedPreferences("radio_launcher",0)
                .getBoolean("wm_auto_bluetooth_audio",true))
            runOnMainSync { audioSwitch.isChecked = true }
            check("automatic Bluetooth audio can be reenabled", context.getSharedPreferences("radio_launcher",0)
                .getBoolean("wm_auto_bluetooth_audio",false))
            runOnMainSync { settings.finish() }
            finish(0,Bundle().apply { putString("stream",checks.joinToString("\n")+"\nWM parameters PASS ${args.getString("orientation")} tests=${checks.size}\n") })
        } catch (error:Throwable) { finish(1,Bundle().apply { putString("stream",checks.joinToString("\n")+"\nFAIL: "+error.stackTraceToString()) }) }
    }
}
