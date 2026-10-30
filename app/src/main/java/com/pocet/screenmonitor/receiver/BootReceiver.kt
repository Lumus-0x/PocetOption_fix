package com.pocet.screenmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocet.screenmonitor.service.CaptureScheduler
import com.pocet.screenmonitor.service.ScreenCaptureService
import timber.log.Timber

class BootReceiver : BroadcastReceiver() {

    companion object {
        // Some devices send this action instead of BOOT_COMPLETED
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("BootReceiver: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT -> {
                // Reschedule periodic work
                val scheduler = CaptureScheduler(context)
                scheduler.rescheduleOnBoot()

                // Also restart the foreground service if it was running
                // Note: This requires MediaProjection which needs user consent
                // The service will need to be restarted from MainActivity after user grants permission
                restartServiceIfNeeded(context)
            }
        }
    }

    private fun restartServiceIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("capture_scheduler_prefs", Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean(CaptureScheduler.PREF_KEY_SERVICE_RUNNING, false)

        if (wasRunning) {
            Timber.d("Service was running before boot, will need user to restart capture")
            // We can't automatically restart MediaProjection without user interaction
            // The user will need to open the app and grant permission again
        }
    }

    companion object {
        fun scheduleExactAlarmOnBoot(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (alarmManager.canScheduleExactAlarms()) {
                    // Reschedule any exact alarms
                    val scheduler = CaptureScheduler(context)
                    scheduler.scheduleExactCapture()
                }
            }
        }
    }
}