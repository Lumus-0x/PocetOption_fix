package com.pocet.screenmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
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
import com.pocet.screenmonitor.model.BalanceResult
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
    private var sheetsRepository: SheetsRepository? = null
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
        initSheetsRepository()
    }

    private fun initSheetsRepository() {
        // SheetsRepository will be initialized when needed via Hilt or manual creation
        // For now, we'll create it lazily when we have the auth manager
    }

    private fun getSheetsRepository(): SheetsRepository? {
        if (sheetsRepository == null) {
            try {
                // Get from Hilt if available, otherwise create manually
                val app = applicationContext as? com.pocet.screenmonitor.PocetApplication
                app?.let { hiltApp ->
                    // Use Hilt to get dependencies
                    // sheetsRepository = hiltApp.hiltComponent.sheetsRepository()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get SheetsRepository from Hilt")
            }
        }
        return sheetsRepository
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_CAPTURE -> {
                // Get MediaProjection from result code and data
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)

                if (resultCode == android.app.Activity.RESULT_OK && resultData != null) {
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val projection = mediaProjectionManager.getMediaProjection(resultCode, resultData)
                    if (projection != null) {
                        startCapture(projection)
                    } else {
                        Timber.e("Failed to create MediaProjection")
                        stopSelf()
                    }
                } else {
                    Timber.e("Invalid MediaProjection data")
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> {
                stopCapture()
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun startCapture(projection: MediaProjection) {
        Timber.d("Starting screen capture")
        mediaProjection = projection

        try {
            setupImageReader()
            createVirtualDisplay()

            isCapturing = true
            showForegroundNotification()
            startCaptureLoop()

            // Register MediaProjection callback
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Timber.d("MediaProjection stopped by system")
                    mainScope.launch {
                        stopCapture()
                        stopSelf()
                    }
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Timber.e(e, "Failed to start screen capture")
            stopCapture()
            stopSelf()
        }
    }

    private fun setupImageReader() {
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920

        try {
            imageReader = ImageReader.newInstance(
                width, height,
                PixelFormat.RGBA_8888,
                2 // Max images
            ).apply {
                setOnImageAvailableListener({ reader ->
                    if (isCapturing) {
                        captureScope.launch {
                            processFrame(reader)
                        }
                    }
                }, Handler(Looper.getMainLooper()))
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to create ImageReader")
            throw e
        }
    }

    private fun createVirtualDisplay() {
        val projection = mediaProjection ?: return
        val width = displayMetrics?.widthPixels ?: 1080
        val height = displayMetrics?.heightPixels ?: 1920
        val density = displayMetrics?.densityDpi ?: 320

        try {
            virtualDisplay = projection.createVirtualDisplay(
                "ScreenCaptureDisplay",
                width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null, // Callback
                null  // Handler
            )

            if (virtualDisplay == null) {
                Timber.e("Failed to create virtual display - returned null")
            }
        } catch (e: Exception) {
            Timber.e(e, "Exception creating virtual display")
            throw e
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
        val image = reader.acquireLatestImage() ?: return

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
                Timber.d("No valid balance detected: ${result.accountType}, confidence: ${result.confidence}")
            }
        } finally {
            image.close()
        }
    }

    private suspend fun processFrame(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return

        try {
            val result = ocrProcessor.processImage(image, targetAccountType)

            if (result.isValid()) {
                Timber.d("Valid balance detected: ${result.accountType} = ${result.amount}")

                val now = System.currentTimeMillis()
                if (now - lastSuccessfulWrite >= MIN_WRITE_INTERVAL_MS) {
                    writeToSheets(result)
                }
            }
        } finally {
            image.close()
        }
    }

    private fun writeToSheets(result: BalanceResult) {
        val repo = getSheetsRepository() ?: return

        captureScope.launch {
            val writeResult = repo.writeBalance(result)
            if (writeResult.isSuccess) {
                Timber.d("Successfully wrote to Google Sheets")
                lastSuccessfulWrite = System.currentTimeMillis()
                updateNotification("Balance synced: ${result.amount}")
            } else {
                Timber.e(writeResult.exceptionOrNull(), "Failed to write to Google Sheets")
                updateNotification("Sync failed: ${writeResult.exceptionOrNull()?.message}")
            }
        }
    }

    private fun stopCapture() {
        Timber.d("Stopping screen capture")
        isCapturing = false

        captureJob.cancel()
        mainJob.cancel()

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.stop()
        mediaProjection = null

        ocrProcessor.close()

        stopForeground(true)
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