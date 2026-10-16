// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/** OEM units and invalid range, verified against this head unit's Launcher.odex. */
internal data class TirePressureReading(val raw: Int?, val warning: Int?) {
    val kpa: Int?
        get() {
            val value = raw ?: return null
            if (warning == 4 || warning == 7 || value !in 1..254) return null
            return (value * 2.75 + 0.5).toInt().takeIf { it in 1..699 }
        }
    val hasWarning: Boolean get() = warning != null && warning != 0
}

/**
 * Foreground-only reader of the OEM CarInfo Binder. Uses the same exposed
 * service lookup as the factory client; denied access stays unavailable.
 * No root, CAN writes, Internet or persistent service. Prefer notifications;
 * a firmware without listener support gets a foreground-only 30s refresh.
 * Contract and wheel order: validation/results-wm5/胎压接口核验.md.
 */
internal class WmTirePressureMonitor(
    private val findService: () -> IBinder? = {
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, SERVICE_NAME) as? IBinder
    },
    private val renderVehicle: ((VehicleReadings) -> Unit)? = null,
    private val render: (List<TirePressureReading>?) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null

    fun start() {
        if (session != null) return
        val current = Session()
        session = current
        current.worker.post { current.connect() }
    }

    fun stop() {
        val old = session ?: return
        session = null
        old.active = false
        old.worker.removeCallbacksAndMessages(null)
        old.worker.post {
            old.detach()
            old.thread.quitSafely()
        }
    }

    private inner class Session {
        val thread = HandlerThread("WM-VehicleReadings").apply { start() }
        val worker = Handler(thread.looper)
        @Volatile var active = true
        private var remote: IBinder? = null
        private var subscribed = false
        private var lastProblem: String? = null
        private val pressures = arrayOfNulls<Int>(4)
        private val warnings = arrayOfNulls<Int>(4)
        private var vehicle = VehicleReadings()
        private val reconnect = Runnable { connect() }
        private val refresh = Runnable { refreshReadings() }
        private val death = IBinder.DeathRecipient {
            worker.post {
                detach()
                publish(null)
                publishVehicle(VehicleReadings())
                if (active) worker.postDelayed(reconnect, 1000)
            }
        }
        private val listener = object : Binder() {
            init { attachInterface(null, LISTENER_DESCRIPTOR) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(LISTENER_DESCRIPTOR)
                    return true
                }
                if (code == 1 || code == 2) {
                    data.enforceInterface(LISTENER_DESCRIPTOR)
                    val position = data.readInt()
                    val type = data.readInt()
                    val value = data.readInt()
                    // The vendor also multiplexes battery events with negative
                    // positions on this callback. They are not tire readings.
                    if (position in 0..3 && type == VALUE_TYPE_PRESSURE) {
                        worker.post {
                            if (active) {
                                if (code == 1) pressures[position] = value else warnings[position] = value
                                publish(readings())
                            }
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
                if (code == 6 || code == 10) {
                    data.enforceInterface(LISTENER_DESCRIPTOR)
                    val type = if (code == 6) data.readInt() else -1
                    val value = data.readInt()
                    worker.post {
                        if (active) {
                            vehicle = when {
                                code == 10 -> vehicle.copy(rangeKm = value)
                                type == 0 -> vehicle.copy(totalTenths = value)
                                type == 1 -> vehicle.copy(tripATenths = value)
                                type == 2 -> vehicle.copy(tripBTenths = value)
                                else -> vehicle
                            }
                            publishVehicle(vehicle)
                        }
                    }
                    reply?.writeNoException()
                    return true
                }
                // Some firmware delivers other read-only CarInfo callbacks to
                // this listener too. Acknowledge them without interpreting them.
                if (code in 3..32) {
                    data.enforceInterface(LISTENER_DESCRIPTOR)
                    reply?.writeNoException()
                    return true
                }
                return super.onTransact(code, data, reply, flags)
            }
        }

        fun connect() {
            if (!active) return
            detach()
            pressures.fill(null)
            warnings.fill(null)
            var stage = "service lookup"
            try {
                // The verified WM firmware is Android 6. Never send numeric
                // vendor transactions to another Android version's service.
                check(Build.VERSION.SDK_INT == 23)
                val binder = findService() ?: error("CarInfo unavailable")
                stage = "descriptor"
                check(binder.interfaceDescriptor == SERVICE_DESCRIPTOR)
                remote = binder
                binder.linkToDeath(death, 0)
                stage = "subscription"
                // In this firmware both TPMS callbacks use listener group 1.
                subscribed = try {
                    transact(REQUEST_LISTENER) {
                        it.writeStrongBinder(listener)
                        it.writeInt(TPMS_LISTENER_GROUP)
                    } != 0
                } catch (_: Exception) {
                    // Some OEM service implementations expose getters but do
                    // not implement / permit callbacks. Keep allowed reads.
                    false
                }
                if (!subscribed) android.util.Log.i("WM-TirePressure",
                    "Factory notifications unavailable; foreground refresh every 30s")
                stage = "initial readings"
                lastProblem = null
                refreshReadings()
            } catch (exception: Exception) {
                val cause = exception.cause ?: exception
                val problem = "$stage: ${cause.javaClass.simpleName}: ${cause.message}"
                if (lastProblem != problem) {
                    android.util.Log.w("WM-TirePressure", problem)
                    lastProblem = problem
                }
                detach()
                publish(null)
                publishVehicle(VehicleReadings())
                // Only retry discovery after an unavailable/restarted service;
                // successful readings are updated solely by OEM notifications.
                if (active && Build.VERSION.SDK_INT == 23) worker.postDelayed(reconnect, 10000)
            }
        }

        private fun refreshReadings() {
            if (!active || remote == null) return
            try {
                for (position in 0..3) {
                    pressures[position] = read(GET_PRESSURE, position)
                    warnings[position] = read(GET_WARNING, position)
                }
                if (active) {
                    publish(readings())
                    if (renderVehicle != null) refreshVehicle()
                    // Odometers / range stay independent of the SOC monitor.
                    if (!subscribed || renderVehicle != null) worker.postDelayed(refresh, 30000)
                }
            } catch (exception: Exception) {
                val problem = "pressure reads: ${exception.javaClass.simpleName}: ${exception.message}"
                if (lastProblem != problem) {
                    android.util.Log.w("WM-TirePressure", problem)
                    lastProblem = problem
                }
                detach()
                publish(null)
                publishVehicle(VehicleReadings())
                if (active) worker.postDelayed(reconnect, 10000)
            }
        }

        private fun readings() = (0..3).map { TirePressureReading(pressures[it], warnings[it]) }

        private fun refreshVehicle() {
            // An unavailable individual field must not erase working TPMS or
            // other fields, and must never be displayed as a fabricated zero.
            fun allowedRead(code: Int, type: Int? = null): Int? = try {
                transact(code) { if (type != null) it.writeInt(type) }
            } catch (_: Exception) { null }
            vehicle = VehicleReadings(null, allowedRead(15), allowedRead(9, 1),
                allowedRead(9, 2), allowedRead(9, 0))
            publishVehicle(vehicle)
        }

        private fun publishVehicle(values: VehicleReadings) {
            main.post { if (active && session === this) renderVehicle?.invoke(values) }
        }

        private fun read(code: Int, position: Int): Int = transact(code) {
            it.writeInt(position)
            it.writeInt(VALUE_TYPE_PRESSURE)
        }

        private fun transact(code: Int, arguments: (Parcel) -> Unit): Int {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE_DESCRIPTOR)
                arguments(data)
                check(remote?.transact(code, data, reply, 0) == true)
                reply.readException()
                return reply.readInt()
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        private fun publish(values: List<TirePressureReading>?) {
            main.post { if (active && session === this) render(values) }
        }

        fun detach() {
            worker.removeCallbacks(reconnect)
            worker.removeCallbacks(refresh)
            val binder = remote
            if (binder != null) {
                if (subscribed && binder.isBinderAlive) {
                    try {
                        transact(REMOVE_LISTENER) {
                            it.writeStrongBinder(listener)
                            it.writeInt(TPMS_LISTENER_GROUP)
                        }
                    } catch (_: Exception) { /* Dead or refused; do not alter OEM service. */ }
                }
                try { binder.unlinkToDeath(death, 0) } catch (_: Exception) { }
            }
            subscribed = false
            remote = null
        }
    }

    companion object {
        private const val SERVICE_NAME = "com.yftech.CarInfoService"
        private const val SERVICE_DESCRIPTOR = "com.yftech.carmanager.ICarInfo"
        private const val LISTENER_DESCRIPTOR = "com.yftech.carmanager.ICarInfoListener"
        private const val REQUEST_LISTENER = 1
        private const val REMOVE_LISTENER = 2
        private const val GET_PRESSURE = 4
        private const val GET_WARNING = 5
        private const val TPMS_LISTENER_GROUP = 1
        private const val VALUE_TYPE_PRESSURE = 1
    }
}
