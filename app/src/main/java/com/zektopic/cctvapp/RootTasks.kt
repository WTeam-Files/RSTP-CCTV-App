package com.zektopic.cctvapp

import android.content.Context
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Things that need root, used only when Magisk has granted this app `su`.
 *
 * On this ROM `persist.adb.tcp.port` survives a reboot but adbd does not open the TCP port
 * from it, so a remote `adb connect` fails after every boot. When the port is closed this
 * sets `service.adb.tcp.port` and restarts adbd, which is exactly what `adb tcpip` does.
 * It never touches adbd while the port is already open, so a live adb session is not cut.
 */
object RootTasks {
    private const val ADB_TCP_PORT = 5555
    private val running = AtomicBoolean(false)

    fun ensureAdbTcp(context: Context) {
        if (!running.compareAndSet(false, true)) return
        Thread {
            try {
                if (portOpen()) return@Thread
                AppLog.w("RootTasks", "adbd is not listening on $ADB_TCP_PORT; re-enabling TCP mode")
                val p = ProcessBuilder(
                    "su", "-c",
                    "setprop persist.adb.tcp.port $ADB_TCP_PORT; setprop service.adb.tcp.port $ADB_TCP_PORT; stop adbd; start adbd"
                ).redirectErrorStream(true).start()
                var exit = -1
                val deadline = System.currentTimeMillis() + 15_000
                while (System.currentTimeMillis() < deadline) {
                    try { exit = p.exitValue(); break } catch (e: IllegalThreadStateException) { Thread.sleep(200) }
                }
                if (exit != 0) {
                    p.destroy()
                    AppLog.e("RootTasks", "Could not re-enable ADB over TCP (su exit=$exit): grant root to the app in Magisk")
                } else {
                    AppLog.i("RootTasks", "ADB over TCP re-enabled on $ADB_TCP_PORT")
                }
            } catch (e: Exception) {
                AppLog.e("RootTasks", "ensureAdbTcp failed", e)
            } finally {
                running.set(false)
            }
        }.start()
    }

    private fun portOpen(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", ADB_TCP_PORT), 400); true }
    } catch (e: Exception) {
        false
    }
}
