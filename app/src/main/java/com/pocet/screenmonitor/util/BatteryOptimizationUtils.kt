package com.pocet.screenmonitor.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import timber.log.Timber

class BatteryOptimizationUtils(private val context: Context) {

    private val powerManager: PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /**
     * Check if app is ignoring battery optimizations
     */
    fun isIgnoringBatteryOptimizations(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        return true // Pre-Marshmallow doesn't have battery optimization
    }

    /**
     * Get intent to request battery optimization exemption from user
     * Caller should launch this via ActivityResultLauncher
     */
    fun getRequestBatteryOptimizationExemptionIntent(): Intent? {
        if (!isIgnoringBatteryOptimizations()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            if (intent.resolveActivity(context.packageManager) != null) {
                return intent
            } else {
                Timber.w("No activity found to handle battery optimization request")
            }
        }
        return null
    }

    /**
     * Check if device is in Doze mode
     */
    fun isDeviceIdleMode(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return powerManager.isDeviceIdleMode
        }
        return false
    }

    /**
     * Check if exact alarm permission is granted (Android 14+)
     */
    fun canScheduleExactAlarms(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            return alarmManager.canScheduleExactAlarms()
        }
        return true
    }

    /**
     * Get intent to request exact alarm permission (Android 14+)
     * Caller should launch this via ActivityResultLauncher
     */
    fun getRequestExactAlarmPermissionIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    return intent
                }
            }
        }
        return null
    }

    /**
     * Check if app has notification permission (Android 13+)
     */
    fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    /**
     * Request notification permission (Android 13+) - runtime permission
     */
    fun requestNotificationPermission(activity: Activity, requestCode: Int = 1003): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasNotificationPermission()) {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    requestCode
                )
                return true
            }
        }
        return false
    }
}