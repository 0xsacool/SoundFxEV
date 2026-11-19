package com.sacool.soundfxev

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/** Launched only by the user's authorised local ADB session, not as an Android service.
 * No network listener, device writes, other commands, files, or long-lived helper.
 * Exits on EOF or if requests stop for 3 seconds.
 */
object BydLinkMain {
    @JvmStatic fun main(args: Array<String>) {
        val deadline = AtomicLong(SystemClock.elapsedRealtime() + 10_000L)
        Thread {
            while (SystemClock.elapsedRealtime() < deadline.get()) Thread.sleep(200L)
            System.exit(0)
        }.apply { isDaemon = true; start() }
        try {
            Looper.prepareMainLooper()
            val cls = Class.forName("android.app.ActivityThread")
            val thread = cls.getMethod("systemMain").invoke(null)
            val context = cls.getMethod("getSystemContext").invoke(thread) as Context
            val reader = BydPedalReader.open(context)
            deadline.set(SystemClock.elapsedRealtime() + 3000L)
            Thread {
                try {
                    val input = BufferedReader(InputStreamReader(System.`in`))
                    System.out.println(BydLinkProtocol.READY)
                    System.out.flush()
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line != "R") break
                        deadline.set(SystemClock.elapsedRealtime() + 3000L)
                        val started = SystemClock.elapsedRealtimeNanos()
                        val sample = reader.read()
                        val at = SystemClock.elapsedRealtimeNanos()
                        if (sample == null || at - started > 300_000_000L) {
                            System.out.println(BydLinkProtocol.INVALID)
                        } else {
                            System.out.println("SFXEV1 SAMPLE $at ${sample.speedKmh} ${sample.pedalPercent}")
                        }
                        System.out.flush()
                        if (System.out.checkError()) break
                    }
                } catch (_: Exception) {
                    System.out.println(BydLinkProtocol.ERROR)
                    System.out.flush()
                } finally {
                    System.exit(0)
                }
            }.apply { isDaemon = true; start() }
            Looper.loop()
        } catch (_: Throwable) {
            System.out.println(BydLinkProtocol.ERROR)
            System.out.flush()
        }
    }
}
