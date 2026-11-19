package com.sacool.soundfxev

import android.content.Context
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal local ADB transport, with no general shell-command interface.
 * The only opened stream is the fixed read-only helper in this installed APK.
 */
internal class BydAdbSession(private val context: Context) : AutoCloseable {
    @Volatile private var cancelled = false
    @Volatile private var socket: Socket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: OutputStream

    class LinkException(message: String) : IOException(message)
    private data class Packet(val command: Int, val arg0: Int, val arg1: Int, val bytes: ByteArray)

    fun authorize() {
        try { connect(allowPrompt = true) } finally { close() }
    }

    fun stream(onSample: (BydLinkProtocol.Sample) -> Unit) {
        try {
            connect(allowPrompt = false)
            val path = context.applicationInfo.sourceDir
            // Shell receives a fixed command and a safely quoted package path only.
            val quoted = "'" + path.replace("'", "'\\''") + "'"
            val cmd = "CLASSPATH=$quoted exec app_process /system/bin --nice-name=soundfxev_readonly com.sacool.soundfxev.BydLinkMain"
            send(OPEN, 1, 0, ("shell:$cmd\u0000").toByteArray())
            socket?.soTimeout = 5000
            var remoteId = 0
            var ready = false
            val pending = StringBuilder()
            while (!cancelled) {
                val packet = read()
                when (packet.command) {
                    OKAY -> {
                        if (packet.arg1 != 1) throw LinkException("ADB stream mismatch")
                        remoteId = packet.arg0
                    }
                    WRTE -> {
                        if (packet.arg1 != 1 || remoteId == 0 || packet.arg0 != remoteId)
                            throw LinkException("ADB stream mismatch")
                        send(OKAY, 1, remoteId)
                        pending.append(packet.bytes.toString(Charsets.UTF_8))
                        if (pending.length > 8192) throw LinkException("Invalid helper response")
                        var request = false
                        while (true) {
                            val eol = pending.indexOf("\n")
                            if (eol < 0) break
                            val line = pending.substring(0, eol).trim()
                            pending.delete(0, eol + 1)
                            when {
                                line == BydLinkProtocol.ERROR -> throw LinkException("BYD SDK read unavailable")
                                line == BydLinkProtocol.READY && !ready -> { ready = true; request = true }
                                ready && line == BydLinkProtocol.INVALID -> request = true
                                ready -> BydLinkProtocol.parse(line)?.let { onSample(it); request = true }
                            }
                        }
                        if (ready && request && !cancelled) {
                            Thread.sleep(100L)
                            send(WRTE, 1, remoteId, "R\n".toByteArray())
                        }
                    }
                    CLSE -> {
                        send(CLSE, 1, packet.arg0)
                        throw LinkException("Vehicle helper disconnected")
                    }
                }
            }
        } finally { close() }
    }

    private fun connect(allowPrompt: Boolean) {
        if (cancelled) throw LinkException("Cancelled")
        val s = Socket()
        socket = s
        if (cancelled) { s.close(); throw LinkException("Cancelled") }
        try { s.connect(InetSocketAddress("127.0.0.1", 5555), 3000) }
        catch (_: IOException) { throw LinkException("เปิด Network ADB ที่รถก่อน") }
        s.tcpNoDelay = true
        s.soTimeout = 8000
        input = DataInputStream(s.getInputStream())
        output = s.getOutputStream()
        val key = AdbKey.loadOrCreate(context)
        send(CNXN, 0x01000001, MAX_DATA, "host::\u0000".toByteArray())
        var signature = false
        var publicKey = false
        while (!cancelled) {
            val p = read()
            if (p.command == CNXN) return
            if (p.command != AUTH || p.arg0 != 1 || p.bytes.size != 20)
                throw LinkException("ADB connection refused")
            if (!signature) {
                signature = true
                send(AUTH, 2, 0, key.sign(p.bytes))
            } else if (allowPrompt && !publicKey) {
                publicKey = true
                send(AUTH, 3, 0, key.publicKeyText("soundfxev@headunit"))
                s.soTimeout = 90_000
            } else throw LinkException("ADB ยังไม่ได้อนุญาต · กด AUTHORIZE")
        }
        throw LinkException("Cancelled")
    }

    private fun send(command: Int, arg0: Int, arg1: Int, bytes: ByteArray = ByteArray(0)) {
        val checksum = bytes.sumOf { it.toInt() and 0xff }
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(arg1).putInt(bytes.size)
            .putInt(checksum).putInt(command.inv())
        output.write(h.array())
        output.write(bytes)
        output.flush()
    }

    private fun read(): Packet {
        val header = ByteArray(24)
        input.readFully(header)
        val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int
        val arg0 = h.int
        val arg1 = h.int
        val count = h.int
        h.int // newer ADB versions may skip checksum
        val magic = h.int
        if (magic != command.inv() || count !in 0..MAX_DATA) throw LinkException("Invalid ADB frame")
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return Packet(command, arg0, arg1, bytes)
    }

    override fun close() {
        cancelled = true
        runCatching { socket?.close() }
    }

    private companion object {
        const val CNXN = 0x4e584e43
        const val AUTH = 0x48545541
        const val OPEN = 0x4e45504f
        const val OKAY = 0x59414b4f
        const val WRTE = 0x45545257
        const val CLSE = 0x45534c43
        const val MAX_DATA = 256 * 1024
    }
}
