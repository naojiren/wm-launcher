// SPDX-License-Identifier: GPL-3.0-only
package com.dashline.launcher

import android.content.Context
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.EOFException
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher

/** One loopback-only ADB stream per settings action; never accepts a remote host. */
internal class WmLocalAdb(context: Context, private val port: Int = 5555,
    private val firstReplyTimeout: Int = 1500) : AutoCloseable {
    private val keyFile = File(context.noBackupFilesDir, "wm-home-adb.pk8")
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private val deadline = System.currentTimeMillis() + 35_000
    private var nextStream = 0
    private var receivedPacket = false
    private data class Packet(val command: Int, val a: Int, val b: Int, val bytes: ByteArray)

    fun connect() {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), 1500)
            input = DataInputStream(socket.getInputStream())
            send(CNXN, 0x01000000, 4096, "host::wm-home\u0000".toByteArray())
            var signed = false
            var published = false
            while (true) {
                val packet = receive()
                when (packet.command) {
                    CNXN -> return
                    AUTH -> {
                        check(packet.a == 1 && packet.bytes.size == 20) { "调试授权协议异常" }
                        val key = privateKey()
                        if (!signed) {
                            // The AUTH token is already a SHA-1 digest; do not hash it again.
                            val sha1DigestInfo = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b,
                                0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
                            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                            cipher.init(Cipher.ENCRYPT_MODE, key)
                            send(AUTH, 2, 0, cipher.doFinal(sha1DigestInfo + packet.bytes))
                            signed = true
                        } else if (!published) {
                            send(AUTH, 3, 0, publicKey(key))
                            published = true
                        } else error("请允许车机上的调试授权提示，再启用此功能")
                    }
                    else -> error("调试连接未就绪")
                }
            }
        } catch (failure: Exception) {
            val mayRecover = socket.isConnected && !receivedPacket &&
                (failure is SocketTimeoutException || failure is EOFException ||
                    (failure is SocketException && failure !is ConnectException))
            close()
            val message = when (failure) {
                is ConnectException -> "无法连接车机本地调试服务，请打开 ADB 开关后重试。"
                is SocketTimeoutException -> "本地调试服务未回应，请关闭再打开 ADB 开关后重试。"
                else -> "本地调试连接未完成：${failure.message ?: failure.javaClass.simpleName}"
            }
            throw WmAdbHandshakeException(message, failure, mayRecover)
        }
    }

    fun shell(command: String): String {
        require(command.length < 3800 && !command.contains('\u0000'))
        val local = ++nextStream
        send(OPEN, local, 0, ("shell:" + command + "\u0000").toByteArray(Charsets.UTF_8))
        var remote = 0
        val result = ByteArrayOutputStream()
        while (true) {
            val packet = receive()
            // A legacy daemon may still send a close for the previous shell stream.
            // Never let that close complete a new command without returning its result.
            if (packet.b < local && packet.command == CLSE) continue
            check(packet.b == local) { "调试连接通道异常" }
            when (packet.command) {
                OKAY -> { check(remote == 0 || remote == packet.a); remote = packet.a }
                WRTE -> {
                    check(remote != 0 && remote == packet.a && result.size() + packet.bytes.size <= 65_536)
                    result.write(packet.bytes)
                    send(OKAY, local, remote)
                }
                CLSE -> { send(CLSE, local, packet.a); return result.toString("UTF-8") }
                else -> error("调试连接返回未知数据")
            }
        }
    }

    private fun send(command: Int, a: Int, b: Int, bytes: ByteArray = byteArrayOf()) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(a).putInt(b).putInt(bytes.size)
            .putInt(bytes.sumOf { it.toInt() and 255 }).putInt(command.inv()).array()
        socket.getOutputStream().apply { write(header); write(bytes); flush() }
    }
    private fun receive(): Packet {
        val remaining = deadline - System.currentTimeMillis()
        check(remaining > 0) { "调试操作超时，请检查授权后重试" }
        socket.soTimeout = remaining.coerceAtMost(if (receivedPacket) 10_000 else firstReplyTimeout.toLong()).toInt()
        val raw = ByteArray(24).also { input.readFully(it) }
        val header = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val a = header.int
        val b = header.int
        val size = header.int
        val checksum = header.int
        check(header.int == command.inv() && size in 0..4096) { "调试数据校验失败" }
        val bytes = ByteArray(size).also { input.readFully(it) }
        check(bytes.sumOf { it.toInt() and 255 } == checksum) { "调试数据校验失败" }
        receivedPacket = true
        return Packet(command, a, b, bytes)
    }
    private fun privateKey(): RSAPrivateCrtKey {
        if (!keyFile.exists()) {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            keyFile.writeBytes(pair.private.encoded)
            keyFile.setReadable(false, false); keyFile.setWritable(false, false)
            keyFile.setReadable(true, true); keyFile.setWritable(true, true)
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes())) as RSAPrivateCrtKey
    }
    private fun publicKey(key: RSAPrivateCrtKey): ByteArray {
        val n = key.modulus
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32).toLong().toInt()
        fun little(number: BigInteger): ByteArray {
            val big = number.toByteArray().reversedArray()
            return ByteArray(256) { if (it < big.size) big[it] else 0 }
        }
        val public = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN).putInt(64).putInt(n0inv)
            .put(little(n)).put(little(BigInteger.ONE.shiftLeft(4096).mod(n)))
            .putInt(key.publicExponent.toInt()).array()
        return (Base64.encodeToString(public, Base64.NO_WRAP) + " wm-home@localhost\u0000").toByteArray()
    }
    override fun close() { try { socket.close() } catch (_: Exception) { } }
    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
    }
}

internal class WmAdbHandshakeException(message: String, cause: Exception, val mayRecover: Boolean) : IOException(message, cause)
