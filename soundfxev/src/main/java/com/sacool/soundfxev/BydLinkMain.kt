package com.sacool.soundfxev

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/** Authorised local app_process, read-only, exits on link loss. */
object BydLinkMain {
    @JvmStatic fun main(args: Array<String>) {
        val deadline = AtomicLong(SystemClock.elapsedRealtime() + 10_000L)
        Thread {
            while (SystemClock.elapsedRealtime() < deadline.get()) Thread.sleep(200L)
            System.exit(0)
        }.apply { isDaemon = true; start() }
        var stage = "START"
        fun emit(line: String) { System.out.println(line); System.out.flush() }
        fun step(value: String) { stage = value; emit(BydLinkProtocol.STEP + value) }
        fun fail(t: Throwable) = emit("${BydLinkProtocol.ERROR} $stage: ${BydLinkProtocol.describe(t)}")
        try {
            step("LOOPER")
            Looper.prepareMainLooper()
            step("CONTEXT_CLASS")
            val cls = Class.forName("android.app.ActivityThread")
            step("CONTEXT_SYSTEM_MAIN")
            val thread = cls.getMethod("systemMain").invoke(null)
            step("CONTEXT_SYSTEM_CONTEXT")
            val context = cls.getMethod("getSystemContext").invoke(thread) as Context
            val reader = BydPedalReader.open(context, ::step)
            deadline.set(SystemClock.elapsedRealtime() + 3000L)
            Thread {
                try {
                    val input = BufferedReader(InputStreamReader(System.`in`))
                    emit(BydLinkProtocol.READY)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line != "R") break
                        deadline.set(SystemClock.elapsedRealtime() + 3000L)
                        val started = SystemClock.elapsedRealtimeNanos()
                        val reading = reader.read(::step)
                        val at = SystemClock.elapsedRealtimeNanos()
                        emit(BydLinkProtocol.DIAG + BydLinkProtocol.clean(reading.report))
                        if (reading.fatal) {
                            emit("${BydLinkProtocol.ERROR} READ: ${BydLinkProtocol.clean(reading.report)}")
                            break
                        }
                        val speed = reading.speed.value
                        val pedal = reading.pedal.value
                        if (speed == null || pedal == null || at - started > 300_000_000L) {
                            emit(BydLinkProtocol.INVALID)
                        } else {
                            emit("SFXEV1 SAMPLE $at $speed ${pedal.toInt()}")
                        }
                        if (System.out.checkError()) break
                    }
                } catch (t: Throwable) {
                    fail(t)
                } finally {
                    System.exit(0)
                }
            }.apply { isDaemon = true; start() }
            Looper.loop()
        } catch (t: Throwable) {
            fail(t)
        }
    }
}
