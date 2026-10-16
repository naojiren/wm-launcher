// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.Parcel

/** Disposable native Binder fixture. Never included in a release or run on the car. */
object WmParameterFixture {
    @JvmStatic fun main(args: Array<String>) {
        check(Build.VERSION.SDK_INT == 23 && android.os.Process.myUid() == 0)
        val property=Class.forName("android.os.SystemProperties").getMethod("get",String::class.java)
        check(property.invoke(null,"ro.kernel.qemu")=="1" && Build.MODEL!="CQ1301")
        Looper.prepareMainLooper()
        val info=object:Binder() {
            init { attachInterface(null,"com.yftech.carmanager.ICarInfo") }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if (code==INTERFACE_TRANSACTION) return super.onTransact(code,data,reply,flags)
                data.enforceInterface("com.yftech.carmanager.ICarInfo")
                println("READ info $code"); System.out.flush()
                reply!!.writeNoException()
                when(code) {
                    3 -> reply.writeString("MCU-EMU-TEST")
                    31 -> reply.writeString("VIN-EMU-TEST")
                    32 -> reply.writeString("SN-EMU-TEST")
                    4 -> { data.readInt(); val type=data.readInt(); reply.writeInt(if(type==1)100 else if(type==0)65 else 0) }
                    5 -> { data.readInt();data.readInt();reply.writeInt(0) }
                    9 -> reply.writeInt(when(data.readInt()) { 0->673940;1->13;2->7667;else->65535 })
                    13,14 -> { data.readInt();reply.writeIntArray(intArrayOf(12,24,36)) }
                    15 -> reply.writeInt(397)
                    17 -> reply.writeInt(when(data.readInt()) { 4->1000;9->-15;else->65 })
                    18 -> reply.writeInt(0)
                    19 -> reply.writeInt(0)
                    27 -> reply.writeInt(2)
                    34 -> reply.writeInt(-125)
                    else -> reply.writeInt(0)
                }
                return true
            }
        }
        val control=object:Binder() {
            init { attachInterface(null,"yfcarcontrol") }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code==INTERFACE_TRANSACTION)return super.onTransact(code,data,reply,flags)
                data.enforceInterface("yfcarcontrol"); println("READ control $code");System.out.flush()
                val type = if (code == 60) data.readInt() else -1
                reply!!.writeInt(when(type) { 10 -> 980; 6,7 -> 1; else -> 0 });return true
            }
        }
        val air=object:Binder() {
            init { attachInterface(null,"yfaircondition") }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code==INTERFACE_TRANSACTION)return super.onTransact(code,data,reply,flags)
                data.enforceInterface("yfaircondition");println("READ air $code");System.out.flush()
                reply!!.writeInt(if(code==12)-5 else 0);return true
            }
        }
        val setup=object:Binder() {
            init { attachInterface(null,"yfcarsetup") }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code==INTERFACE_TRANSACTION)return super.onTransact(code,data,reply,flags)
                data.enforceInterface("yfcarsetup");println("READ setup $code");System.out.flush()
                reply!!.writeInt(if(code==21)1 else 0);return true
            }
        }
        val bluetooth=object:Binder() {
            init { attachInterface(null,"com.yftech.btmanager.IBtManager") }
            override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
                if(code==INTERFACE_TRANSACTION)return super.onTransact(code,data,reply,flags)
                data.enforceInterface("com.yftech.btmanager.IBtManager")
                val result = when(code) {
                    1 -> { data.readStrongBinder(); check(data.readInt()==4); 1 }
                    2 -> { data.readStrongBinder(); check(data.readInt()==4)
                        println("AUDIO-DETACH 4");System.out.flush();1 }
                    7 -> { check(data.readInt()==49); 2 }
                    11 -> { check(data.readInt()==134); check(data.readString()==null)
                        println("AUDIO-ROUTE 134");System.out.flush();1 }
                    else -> error("Unexpected fixture Bluetooth transaction")
                }
                check(data.dataAvail()==0); reply!!.writeNoException(); reply.writeInt(result);return true
            }
        }
        val manager=Class.forName("android.os.ServiceManager").getMethod("addService",String::class.java,IBinder::class.java)
        for ((name,binder) in listOf("com.yftech.CarInfoService" to info,"yfcarcontrol" to control,"yfaircondition" to air,
            "yfcarsetup" to setup,"com.yftech.btservice.Bluetooth" to bluetooth))
            manager.invoke(null,name,binder)
        println("READY parameter fixture");System.out.flush();Looper.loop()
    }
}
