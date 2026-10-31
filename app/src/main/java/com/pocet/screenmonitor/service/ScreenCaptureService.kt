package com.pocet.screenmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pocet.screenmonitor.MainActivity
import com.pocet.screenmonitor.R
import com.pocet.screenmonitor.di.ServiceEntryPoint
import com.pocet.screenmonitor.model.BalanceResult
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START_CAPTURE = "com.pocet.screenmonitor.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.pocet.screenmonitor.STOP_CAPTURE"
        const val ACTION_CAPTURE_STARTED = "com.pocet.screenmonitor.CAPTURE_STARTED"
        const val ACTION_CAPTURE_STOPPED = "com.pocet.screenmonitor.CAPTURE_STOPPED"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val PREFS_NAME = "capture_prefs"
        const val PREF_KEY_ACCOUNT_TYPE = "target_account_type"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val CAPTURE_INTERVAL_MS = 10000L // 10 seconds
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayMetrics: DisplayMetrics? = null
    private var windowManager: WindowManager? = null

    private val ocrProcessor = OcrProcessor()
    private val textParser = TextParser()
    private val captureJob = SupervisorJob()
    private val mainJob = SupervisorJob()
    private val captureScope = CoroutineScope(captureJob + Dispatchers.IO)
    private val mainScope = CoroutineScope(mainJob + Dispatchers.Main)
    private var isCapturing = false
    private var lastSuccessfulWrite: Long = 0
    private val MIN_WRITE_INTERVAL_MS = 60000L // Minimum 1 minute between writes
    private var targetAccountType: BalanceResult.AccountType = BalanceResult.AccountType.REAL

    override fun onCreate() {
        super.onCreate()
        Timber.d("ScreenCaptureService onCreate")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayMetrics = DisplayMetrics()
        windowManager?.defaultDisplay?.getMetrics(displayMetrics!!)

        // Load target account type preference
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val accountTypeStr = prefs.getString(PREF_KEY_ACCOUNT_TYPE, "REAL") ?: "REAL"
        targetAccountType = BalanceResult.AccountType.valueOf(accountTypeStr)
        Timber.d("Target account type: $targetAccountType")

        createNotificationChannel()
    }

    private fun getAppsScriptClient(): AppsScriptClient? {
        return try {
            val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ServiceEntryPoint::class.java)
            entryPoint.appsScriptClient().get()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get AppsScriptClient from Hilt EntryPoint")
            null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand: action=${intent?.action}, flags=$flags, startId=$startId")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                // Get MediaProjection from result code and data
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                Timber.d("MediaProjection data: resultCode=$resultCode, resultData=${resultData != null}")

                if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                    // Start foreground service FIRST with correct type (required on Android 14+)
                    // Then create MediaProjection
                    startForegroundServiceWithType()

                    // Small delay to ensure foreground service is fully active (fixes Android 14+ race condition)
                    mainScope.launch {
                        try {
                            delay(500) // Wait for foreground service to be fully registered
                        } catch (e: Exception) {
                            Timber.d("Delay interrupted")
                            return@launch
                        }

                        try {
                            val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            Timber.d("Getting MediaProjection from manager")
                            val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                            if (projection != null) {
                                Timber.d("MediaProjection created successfully")
                                startCapture(projection)
                            } else {
                                Timber.e("Failed to create MediaProjection - returned null")
                                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                                stopSelf()
                            }
                        } catch (e: Exception) {
                            Timber.e(e, "Error creating MediaProjection")
                            sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                            stopSelf()
                        }
                    }
                } else {
                    Timber.e("Invalid MediaProjection data: resultCode=$resultCode, resultData=${resultData != null}")
                    sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> {
                Timber.d("ACTION_STOP_CAPTURE received")
                stopCapture()
                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Start foreground service with MEDIA_PROJECTION type (required on Android 14+)
     * Must be called BEFORE creating MediaProjection
     */
    private fun startForegroundServiceWithType() {
        Timber.d("Starting foreground service with MEDIA_PROJECTION type")

        // Create intent to open MainActivity when notification is tapped
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT) // Higher priority for visibility
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29): use startForeground with foregroundServiceType
            // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION = 8 (added in API 34)
            val mediaProjectionType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                // For Android 10-13, use the constant value directly
                8 // FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            Timber.d("Calling startForeground with type: $mediaProjectionType (SDK: ${Build.VERSION.SDK_INT})")
            try {
                startForeground(NOTIFICATION_ID, notification, mediaProjectionType)
                Timber.d("startForeground SUCCESS")
            } catch (e: Exception) {
                Timber.e(e, "startForeground FAILED: ${e.message}")
                throw e
            }
        } else {
            Timber.d("Calling startForeground without type (SDK < 29)")
            try {
                startForeground(NOTIFICATION_ID, notification)
                Timber.d("startForeground SUCCESS (no type)")
            } catch (e: Exception) {
                Timber.e(e, "startForeground FAILED (no type): ${e.message}")
                throw e
            }
        }
    }

    private fun startCapture(projection: MediaProjection) {
        Timber.d("Starting screen capture")
        mediaProjection = projection

        try {
            setupImageReader()
            val vdCreated = createVirtualDisplay()

            if (!vdCreated || virtualDisplay == null) {
                Timber.e("VirtualDisplay creation failed!")
                sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                stopCapture()
                stopSelf()
                return
            }

            isCapturing = true
            // Foreground already started in onStartCommand, just update notification if needed
            startCaptureLoop()

            // Register MediaProjection callback
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.d("MediaProjection stopped by system")
                    mainScope.launch {
                        stopCapture()
                        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                        stopSelf()
                    }
                }
            }, Handler(Looper.getMainLooper()))

            // Notify that capture started successfully
            sendBroadcast(Intent(ACTION_CAPTURE_STARTED))
            Timber.d("Screen capture started successfully, capture loop running")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start screen capture")
            sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
            stopCapture()
            stopSelf()
        }
    }

    private fun setupImageReader() {
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920
        Timber.d("Setting up ImageReader: ${width}x${height}, format=RGBA_8888")

        try {
            imageReader = ImageReader.newInstance(
                width, height,
                PixelFormat.RGBA_8888,
                2 // Max images
            ).apply {
                // We don't use OnImageAvailableListener - instead we use periodic capture in startCaptureLoop()
                // This avoids race conditions and double-processing
            }
            Timber.d("ImageReader created successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ImageReader")
            throw e
        }
    }

    private fun createVirtualDisplay(): Boolean {
        val projection = mediaProjection ?: return false
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920
        val density = displayMetrics?.densityDpi ?: 320
        val surface = imageReader?.surface

        Timber.d("Creating VirtualDisplay: ${width}x${height}, density=$density, surface=${surface != null}, projectionValid=${projection != null}")

        // Validate surface
        if (surface == null) {
            Timber.e("ImageReader surface is null - cannot create VirtualDisplay")
            return false
        }

        // Validate projection
        if (projection == null) {
            Timber.e("MediaProjection is null - cannot create VirtualDisplay")
            return false
        }

        try {
            val vd = projection.createVirtualDisplay(
                "ScreenCaptureDisplay",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        Timber.w("VirtualDisplay paused")
                    }

                    override fun onResumed() {
                        Timber.d("VirtualDisplay resumed")
                    }

                    override fun onStopped() {
                        Timber.w("VirtualDisplay stopped")
                        mainScope.launch {
                            stopCapture()
                            sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
                            stopSelf()
                        }
                    }
                },
                Handler(Looper.getMainLooper())
            )

            virtualDisplay = vd

            if (vd == null) {
                Timber.e("Failed to create virtual display - returned null")
                return false
            } else {
                Timber.d("VirtualDisplay created successfully: ${vd.display}")
                return true
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception creating virtual display: ${e.message}, cause: ${e.cause?.message}")
            return false
        }
    }

    private fun startCaptureLoop() {
        captureScope.launch {
            while (isCapturing && captureScope.isActive) {
                try {
                    captureAndProcessFrame()
                } catch (e: Exception) {
                    Timber.e(e, "Error in capture loop")
                }
                delay(CAPTURE_INTERVAL_MS)
            }
        }
    }

    private suspend fun captureAndProcessFrame() {
        val reader = imageReader ?: return
        val image = reader.acquireLatestImage()
        if (image == null) {
            Timber.w("acquireLatestImage returned null - no frame available")
            return
        }

        Timber.d("Captured frame: ${image.width}x${image.height}, format=${image.format}")

        try {
            // Process with OCR - pass target account type for focused detection
            val result = ocrProcessor.processImage(image, targetAccountType)

            if (result.isValid()) {
                Timber.d("Valid balance detected: ${result.accountType} = ${result.amount}")

                // Check if enough time has passed since last write
                val now = System.currentTimeMillis()
                if (now - lastSuccessfulWrite >= MIN_WRITE_INTERVAL_MS) {
                    writeToSheets(result)
                } else {
                    Timber.d("Skipping write - too soon since last write")
                }
            } else {
                Timber.d("No valid balance detected: ${result.accountType}, confidence: ${result.confidence}, rawText: ${result.rawText?.take(100)}")
            }
        } finally {
            image.close()
        }
    }

    private fun writeToSheets(result: BalanceResult) {
        val client = getAppsScriptClient() ?: return

        captureScope.launch {
            val writeResult = client.sendBalance(result)
            if (writeResult.isSuccess) {
                Timber.d("Successfully sent to Apps Script")
                lastSuccessfulWrite = System.currentTimeMillis()
                updateNotification("Balance synced: ${result.amount}")
            } else {
                Timber.e("Failed to send to Apps Script: ${writeResult.errorMessage}")
                updateNotification("Sync failed: ${writeResult.errorMessage}")
            }
        }
    }

    private fun stopCapture() {
        Timber.d("Stopping screen capture")
        isCapturing = false

        // Cancel coroutines safely
        captureJob.cancel()
        mainJob.cancel()

        // Release virtual display
        virtualDisplay?.let {
            try {
                it.release()
                Timber.d("Virtual display released")
            } catch (e: Exception) {
                Timber.e(e, "Error releasing virtual display")
            }
        }
        virtualDisplay = null

        // Close image reader
        imageReader?.let {
            try {
                it.close()
                Timber.d("Image reader closed")
            } catch (e: Exception) {
                Timber.e(e, "Error closing image reader")
            }
        }
        imageReader = null

        // Stop media projection
        mediaProjection?.let {
            try {
                it.stop()
                Timber.d("Media projection stopped")
            } catch (e: Exception) {
                Timber.e(e, "Error stopping media projection")
            }
        }
        mediaProjection = null

        // Close OCR processor
        try {
            ocrProcessor.close()
            Timber.d("OCR processor closed")
        } catch (e: Exception) {
            Timber.e(e, "Error closing OCR processor")
        }

        // Stop foreground service
        try {
            stopForeground(true)
            Timber.d("Foreground service stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping foreground service")
        }

        // Notify that capture stopped
        sendBroadcast(Intent(ACTION_CAPTURE_STOPPED))
    }

    private fun showForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                enableVibration(false)
                // sound and audioAttributes are left as default (null) for silent notification
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        Timber.d("ScreenCaptureService onDestroy")
        stopCapture()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.d("Task removed, stopping service")
        stopCapture()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}