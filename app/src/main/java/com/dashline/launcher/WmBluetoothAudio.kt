// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import org.json.JSONObject

/** Same Binder contract as the factory Music / BtService clients, Android 6 only. */
internal object WmBluetoothRoute {
    const val SERVICE = "com.yftech.btservice.Bluetooth"
    const val DESCRIPTOR = "com.yftech.btmanager.IBtManager"
    const val LISTENER = "com.yftech.btmanager.IBtListener"
    fun exchange(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int {
        require(code in setOf(1, 2, 7, 11))
        check(Build.VERSION.SDK_INT == 23 && binder.interfaceDescriptor == DESCRIPTOR)
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR); write(data)
            check(binder.transact(code, data, reply, 0))
            reply.readException(); check(reply.dataAvail() == 4)
            return reply.readInt()
        } finally { reply.recycle(); data.recycle() }
    }
    fun state(binder: IBinder): Int? = exchange(binder, 7) { it.writeInt(49) }.takeIf { it in 0..3 }
    fun route(binder: IBinder): Boolean {
        // Recheck the media profile immediately before the sole write operation.
        if (state(binder) != 2) return false
        // Factory Music PLAY → reqAvrcpMusicControl(PLAY=2) → reqBtAction(134).
        // BtService handles its own audio focus and A2DP renderer together.
        return exchange(binder, 11) { it.writeInt(134); it.writeString(null) } == 1
    }
    fun command(enableRoute: Boolean): JSONObject {
        val binder = WmDetailBinder.service(SERVICE) ?: error("原厂蓝牙服务不可用")
        val state = state(binder)
        return JSONObject().put("state", state ?: JSONObject.NULL)
            .put("routed", enableRoute && state == 2 && route(binder))
    }
}

/** At most one successful switch per media connection, with three bounded attempts. */
internal class WmBluetoothConnectionPolicy {
    private var connected = false
    private var switched = false
    private var attempts = 0
    fun observe(state: Int?): Boolean {
        if (state == 0 || state == 3) { connected = false; switched = false; attempts = 0 }
        if (state != 2) return false
        if (!connected) { connected = true; switched = false; attempts = 0 }
        return !switched && attempts < 3
    }
    fun attempted(success: Boolean) { attempts++; if (success) switched = true }
}

/** OEM event listener stays idle between connections; no audio polling or foreground service. */
internal object WmBluetoothAudio {
    private var session: Session? = null
    fun refresh(context: Context) {
        if (Build.VERSION.SDK_INT != 23) return
        val app = context.applicationContext
        val enabled = app.getSharedPreferences("radio_launcher", Context.MODE_PRIVATE)
            .getBoolean("wm_auto_bluetooth_audio", true)
        synchronized(this) {
            if (!enabled) { session?.stop(); session = null; return }
            val current = session ?: Session(app).also { session = it }
            current.worker.post { current.connect() }
        }
    }
    private class Session(val context: Context) {
        val thread = HandlerThread("WM-BluetoothAudio").apply { start() }
        val worker = Handler(thread.looper)
        @Volatile private var active = true
        private var remote: IBinder? = null
        private var subscribed = false
        private var discoveries = 0
        private val policy = WmBluetoothConnectionPolicy()
        private val retry = Runnable { checkConnection() }
        private val death = IBinder.DeathRecipient { worker.post { detach(); if (active) connect() } }
        private val listener = object : Binder() {
            init { attachInterface(null, WmBluetoothRoute.LISTENER) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) { reply?.writeString(WmBluetoothRoute.LISTENER); return true }
                if (code !in 1..2) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(WmBluetoothRoute.LISTENER)
                val type = data.readInt(); data.readString() // Address is not stored or logged.
                if (code == 1) {
                    val state = data.readInt()
                    if (type == 49) worker.post {
                        if (active) {
                            worker.removeCallbacks(retry)
                            if (policy.observe(state)) worker.postDelayed(retry, 800)
                        }
                    }
                } else data.readString()
                reply?.writeNoException(); return true
            }
        }
        fun connect() {
            if (!active) return
            if (remote?.isBinderAlive != true) {
                detach()
                remote = WmDetailBinder.service(WmBluetoothRoute.SERVICE)
                val binder = remote
                if (binder == null) {
                    if (++discoveries <= 3) worker.postDelayed({ connect() }, 3000)
                    return
                }
                runCatching {
                    binder.linkToDeath(death, 0)
                    subscribed = WmBluetoothRoute.exchange(binder, 1) {
                        it.writeStrongBinder(listener); it.writeInt(4)
                    } == 1
                }
            }
            checkConnection()
        }
        private fun checkConnection() {
            if (!active) return
            val state = try { remote?.let { WmBluetoothRoute.state(it) } } catch (_: Exception) { null }
            val observed = state ?: runCatching {
                val result = WmVehicleRoot.result(WmVehicleRoot.execute(context, "bt-status"))
                check(result.optBoolean("ok")); (result.opt("state") as? Number)?.toInt()
            }.getOrNull()
            if (!active || !policy.observe(observed)) return
            // Do not retry an ambiguous write through a different transport.
            val success = runCatching {
                if (state != null) remote?.let { WmBluetoothRoute.route(it) } == true
                else WmVehicleRoot.result(WmVehicleRoot.execute(context, "bt-route")).optBoolean("routed")
            }.getOrDefault(false)
            policy.attempted(success)
            if (!success && active) worker.postDelayed(retry, 2500)
        }
        private fun detach() {
            worker.removeCallbacks(retry)
            policy.observe(0)
            remote?.let { binder ->
                if (subscribed && binder.isBinderAlive) runCatching {
                    WmBluetoothRoute.exchange(binder, 2) { it.writeStrongBinder(listener); it.writeInt(4) }
                }
                runCatching { binder.unlinkToDeath(death, 0) }
            }
            subscribed = false; remote = null
        }
        fun stop() { active = false; worker.removeCallbacksAndMessages(null); worker.post { detach(); thread.quitSafely() } }
    }
}

/** Standard stack broadcasts also wake a killed launcher process on compatible revisions. */
class WmBluetoothConnectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf("android.bluetooth.device.action.ACL_CONNECTED",
            "android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")) WmBluetoothAudio.refresh(context)
    }
}
