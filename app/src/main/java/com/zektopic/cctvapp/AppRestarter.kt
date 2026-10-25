package com.zektopic.cctvapp

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process

/**
 * Restarts the whole app process: schedules the main activity to come back a moment later,
 * then exits. Coming back through an activity (allowed from the background because the app
 * holds SYSTEM_ALERT_WINDOW) gives the new process a foreground state for the camera, which a
 * bare service restart on Android 11 does not.
 */
object AppRestarter {
    fun restart(context: Context, delayMs: Long = 2_500L) {
        try {
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pending = PendingIntent.getActivity(
                context, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val alarms = context.getSystemService(AlarmManager::class.java)
            val at = System.currentTimeMillis() + delayMs
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } catch (e: Exception) {
                alarms.set(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: Exception) {
            AppLog.w("AppRestarter", "Could not schedule relaunch", e)
        }
        AppLog.i("AppRestarter", "Exiting process for restart")
        Process.killProcess(Process.myPid())
    }
}
