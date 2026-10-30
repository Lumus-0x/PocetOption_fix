package com.pocet.screenmonitor.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.pocet.screenmonitor.util.BatteryOptimizationUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.TimeUnit

class CaptureScheduler(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val batteryUtils = BatteryOptimizationUtils(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("capture_scheduler_prefs", Context.MODE_PRIVATE)
    private val scheduleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val WORK_NAME = "balance_capture_work"
        const val WORK_TAG = "balance_capture"
        const val CAPTURE_INTERVAL_MINUTES = 15L
        const val PREF_KEY_LAST_SCHEDULE = "last_schedule_time"
        const val PREF_KEY_SERVICE_RUNNING = "service_running"
    }

    /**
     * Schedule periodic capture using WorkManager (recommended for Android 16)
     */
    fun schedulePeriodicCapture() {
        scheduleScope.launch {
            // Check constraints
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val captureWork = PeriodicWorkRequest.Builder(CaptureWorker::class.java, CAPTURE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                captureWork
            )

            prefs.edit().putLong(PREF_KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
            Timber.d("Scheduled periodic capture work")
        }
    }

    /**
     * Schedule exact capture using AlarmManager (for more precise timing)
     */
    fun scheduleExactCapture(intervalMinutes: Long = 15) {
        if (!batteryUtils.canScheduleExactAlarms()) {
            Timber.w("Cannot schedule exact alarms - permission not granted")
            return
        }

        scheduleScope.launch {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, CaptureWorker::class.java).apply {
                action = CaptureWorker.ACTION_TRIGGER_CAPTURE
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val intervalMillis = intervalMinutes * 60 * 1000
            val triggerAtMillis = SystemClock.elapsedRealtime() + intervalMillis

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }

            prefs.edit().putLong(PREF_KEY_LAST_SCHEDULE, System.currentTimeMillis()).apply()
            Timber.d("Scheduled exact capture for $intervalMinutes minutes")
        }
    }

    /**
     * Trigger immediate capture
     */
    fun triggerImmediateCapture() {
        scheduleScope.launch {
            val immediateWork = OneTimeWorkRequest.Builder(CaptureWorker::class.java)
                .addTag(WORK_TAG)
                .build()
            workManager.enqueue(immediateWork)
            Timber.d("Triggered immediate capture")
        }
    }

    /**
     * Cancel all scheduled work
     */
    fun cancelAll() {
        scheduleScope.launch {
            workManager.cancelAllWorkByTag(WORK_TAG)
            workManager.cancelUniqueWork(WORK_NAME)
            Timber.d("Cancelled all capture work")
        }
    }

    /**
     * Check if service should be running and restart if needed
     */
    fun ensureServiceRunning() {
        val isRunning = prefs.getBoolean(PREF_KEY_SERVICE_RUNNING, false)
        if (isRunning) {
            // Check if WorkManager work is still enqueued
            workManager.getWorkInfosByTagLiveData(WORK_TAG).observeForever { workInfos ->
                val hasRunningWork = workInfos.any { it.state.isFinished.not() }
                if (!hasRunningWork) {
                    Timber.d("No running work found, rescheduling")
                    schedulePeriodicCapture()
                }
            }
        }
    }

    /**
     * Mark service as running/stopped
     */
    fun setServiceRunning(running: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_SERVICE_RUNNING, running).apply()
    }

    /**
     * Check if service is marked as running
     */
    fun isServiceRunning(): Boolean {
        return prefs.getBoolean(PREF_KEY_SERVICE_RUNNING, false)
    }

    /**
     * Reschedule on boot
     */
    fun rescheduleOnBoot() {
        scheduleScope.launch {
            // Small delay to let system settle
            Thread.sleep(5000)
            schedulePeriodicCapture()
            setServiceRunning(true)
        }
    }
}

/**
 * Worker for periodic screen capture
 */
class CaptureWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val ACTION_TRIGGER_CAPTURE = "com.pocet.screenmonitor.TRIGGER_CAPTURE"
    }

    override fun doWork(): Result {
        Timber.d("CaptureWorker: Starting capture")

        try {
            // Check if we have MediaProjection permission
            // This would typically be handled by starting the foreground service
            // For now, we'll just trigger the service if it's not running

            val intent = Intent(applicationContext, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                // MediaProjection data would need to be passed here
                // In practice, this needs to be started from an Activity with the projection data
            }

            // Start the foreground service
            // Note: This requires the MediaProjection data from user consent
            // The actual capture is managed by the service itself once started

            return Result.success()
        } catch (e: Exception) {
            Timber.e(e, "CaptureWorker failed")
            return Result.retry()
        }
    }
}