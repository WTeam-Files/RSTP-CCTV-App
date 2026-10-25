package com.zektopic.cctvapp

import android.content.Context
import android.provider.Settings

/**
 * Keeps the phone behaving like a camera appliance: screen on while plugged in, no screensaver.
 *
 * These are global/secure settings, which an app can only write once WRITE_SECURE_SETTINGS has
 * been granted over adb (`pm grant ... android.permission.WRITE_SECURE_SETTINGS`). Without the
 * grant every write is skipped and reported, never thrown. Re-applied at every boot and service
 * start so a settings reset cannot silently undo it.
 */
object KioskSettings {

    /** STAY_ON_WHILE_PLUGGED_IN bit mask: AC (1) | USB (2) | wireless (4). */
    private const val STAY_ON_ALL = 7

    fun apply(context: Context): String {
        val resolver = context.contentResolver
        val done = ArrayList<String>()
        val skipped = ArrayList<String>()
        fun attempt(name: String, block: () -> Unit) {
            try { block(); done.add(name) } catch (e: Exception) { skipped.add(name) }
        }
        attempt("stay_on_while_plugged_in") {
            if (Settings.Global.getInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != STAY_ON_ALL) {
                Settings.Global.putInt(resolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, STAY_ON_ALL)
            }
        }
        for (key in listOf("screensaver_enabled", "screensaver_activate_on_dock", "screensaver_activate_on_sleep")) {
            attempt(key) {
                if (Settings.Secure.getInt(resolver, key, 1) != 0) Settings.Secure.putInt(resolver, key, 0)
            }
        }
        val summary = "kiosk settings applied=$done skipped=$skipped"
        AppLog.i("Kiosk", summary)
        return summary
    }
}
