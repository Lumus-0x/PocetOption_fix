package com.pocet.screenmonitor.util

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {

    // MediaProjection is handled via system intent, not runtime permission
    // These are the runtime permissions we need to request

    private const val REQUEST_CODE_PERMISSIONS = 2000

    val REQUIRED_PERMISSIONS = arrayOf(
        android.Manifest.permission.INTERNET,
        android.Manifest.permission.ACCESS_NETWORK_STATE,
        android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
        android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
    )

    // Android 13+ notification permission
    val NOTIFICATION_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)
    } else {
        emptyArray<String>()
    }

    // Android 14+ exact alarm permission
    val EXACT_ALARM_PERMISSION = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(android.Manifest.permission.SCHEDULE_EXACT_ALARM, android.Manifest.permission.USE_EXACT_ALARM)
    } else {
        emptyArray<String>()
    }

    fun checkAllPermissions(context: Context): Boolean {
        val allPermissions = REQUIRED_PERMISSIONS + NOTIFICATION_PERMISSION + EXACT_ALARM_PERMISSION
        return allPermissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun requestAllPermissions(activity: Activity) {
        val permissionsToRequest = mutableListOf<String>()

        for (permission in REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        for (permission in NOTIFICATION_PERMISSION) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        for (permission in EXACT_ALARM_PERMISSION) {
            if (ContextCompat.checkSelfPermission(activity, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                permissionsToRequest.toTypedArray(),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    fun shouldShowRequestPermissionRationale(activity: Activity): Boolean {
        val allPermissions = REQUIRED_PERMISSIONS + NOTIFICATION_PERMISSION + EXACT_ALARM_PERMISSION
        return allPermissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
    }
}