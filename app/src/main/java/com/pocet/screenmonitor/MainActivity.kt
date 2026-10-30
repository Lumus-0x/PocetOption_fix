package com.pocet.screenmonitor

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.pocet.screenmonitor.service.CaptureScheduler
import com.pocet.screenmonitor.service.ScreenCaptureService
import com.pocet.screenmonitor.service.SheetsAuthManager
import com.pocet.screenmonitor.service.SheetsRepository
import com.pocet.screenmonitor.util.BatteryOptimizationUtils
import com.pocet.screenmonitor.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var authManager: SheetsAuthManager
    private lateinit var captureScheduler: CaptureScheduler
    private lateinit var batteryUtils: BatteryOptimizationUtils

    // UI Elements
    private lateinit var tvStatus: TextView
    private lateinit var tvLastSync: TextView
    private lateinit var btnStartCapture: Button
    private lateinit var btnStopCapture: Button
    private lateinit var btnSignIn: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnRequestPermissions: Button
    private lateinit var rgAccountType: RadioGroup
    private lateinit var rbReal: RadioButton
    private lateinit var rbDemo: RadioButton

    // Activity Result Launchers
    private val mediaProjectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleMediaProjectionResult(result.resultCode, result.data)
    }

    private val signInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleSignInResult(result.resultCode, result.data)
    }

    private val batteryOptimizationLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkBatteryOptimization()
    }

    private val exactAlarmLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        checkExactAlarmPermission()
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Timber.d("Notification permission granted")
        } else {
            Timber.w("Notification permission denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initManagers()
        setupClickListeners()
        checkPermissionsAndSetup()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvLastSync = findViewById(R.id.tvLastSync)
        btnStartCapture = findViewById(R.id.btnStartCapture)
        btnStopCapture = findViewById(R.id.btnStopCapture)
        btnSignIn = findViewById(R.id.btnSignIn)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnRequestPermissions = findViewById(R.id.btnRequestPermissions)
        rgAccountType = findViewById(R.id.rgAccountType)
        rbReal = findViewById(R.id.rbReal)
        rbDemo = findViewById(R.id.rbDemo)

        // Load saved account type preference
        loadAccountTypePreference()

        // Set listener for account type changes
        rgAccountType.setOnCheckedChangeListener { _, checkedId ->
            saveAccountTypePreference(checkedId)
        }
    }

    private fun initManagers() {
        authManager = SheetsAuthManager(this)
        captureScheduler = CaptureScheduler(this)
        batteryUtils = BatteryOptimizationUtils(this)

        // Initialize auth manager with sign-in options
        val signInOptions = com.google.android.gms.auth.api.signin.GoogleSignInOptions.Builder(
            com.google.android.gms.auth.api.signin.GoogleSignInOptions.DEFAULT_SIGN_IN
        )
            .requestScopes(com.google.android.gms.common.api.Scope(com.google.api.services.sheets.v4.SheetsScopes.SPREADSHEETS))
            .requestEmail()
            .build()
        authManager.initialize(signInOptions)
    }

    private fun setupClickListeners() {
        btnStartCapture.setOnClickListener { requestMediaProjection() }
        btnStopCapture.setOnClickListener { stopCaptureService() }
        btnSignIn.setOnClickListener { signInToGoogle() }
        btnTestConnection.setOnClickListener { testSheetsConnection() }
        btnRequestPermissions.setOnClickListener { requestAllPermissions() }
    }

    private fun checkPermissionsAndSetup() {
        lifecycleScope.launch {
            // Check battery optimization
            checkBatteryOptimization()

            // Check exact alarm permission
            checkExactAlarmPermission()

            // Check notification permission
            checkNotificationPermission()

            // Check if signed in
            checkSignInStatus()

            // Update UI based on service state
            updateServiceStatusUI()
        }
    }

    private fun loadAccountTypePreference() {
        val prefs = getSharedPreferences(ScreenCaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        val accountType = prefs.getString(ScreenCaptureService.PREF_KEY_ACCOUNT_TYPE, "REAL")
        when (accountType) {
            "REAL" -> rbReal.isChecked = true
            "DEMO" -> rbDemo.isChecked = true
        }
    }

    private fun saveAccountTypePreference(checkedId: Int) {
        val accountType = when (checkedId) {
            R.id.rbReal -> "REAL"
            R.id.rbDemo -> "DEMO"
            else -> "REAL"
        }
        val prefs = getSharedPreferences(ScreenCaptureService.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(ScreenCaptureService.PREF_KEY_ACCOUNT_TYPE, accountType).apply()
        Timber.d("Saved account type preference: $accountType")

        // If service is running, we need to restart it to pick up the new preference
        if (captureScheduler.isServiceRunning()) {
            updateStatus("⚠️ Account type changed. Restart capture to apply.")
        }
    }

    private fun requestAllPermissions() {
        PermissionUtils.requestAllPermissions(this)

        // Also request special permissions
        if (!batteryUtils.isIgnoringBatteryOptimizations()) {
            batteryUtils.requestBatteryOptimizationExemption(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !batteryUtils.canScheduleExactAlarms()) {
            batteryUtils.requestExactAlarmPermission(this)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !batteryUtils.hasNotificationPermission()) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun checkBatteryOptimization() {
        if (!batteryUtils.isIgnoringBatteryOptimizations()) {
            updateStatus("⚠️ Battery optimization not disabled - app may be killed in background")
        }
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !batteryUtils.canScheduleExactAlarms()) {
            updateStatus("⚠️ Exact alarm permission not granted - scheduling may be inexact")
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !batteryUtils.hasNotificationPermission()) {
            updateStatus("⚠️ Notification permission not granted")
        }
    }

    private fun checkSignInStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val isSignedIn = authManager.isSignedIn()
            runOnUiThread {
                if (isSignedIn) {
                    btnSignIn.text = "Signed In ✓"
                    btnSignIn.isEnabled = false
                    updateStatus("✅ Google account connected")
                } else {
                    btnSignIn.text = "Sign In to Google"
                    btnSignIn.isEnabled = true
                    updateStatus("❌ Not signed in to Google")
                }
            }
        }
    }

    private fun requestMediaProjection() {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        mediaProjectionLauncher.launch(intent)
    }

    private fun handleMediaProjectionResult(resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            Timber.d("MediaProjection granted")

            // Start the foreground service with the projection data
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START_CAPTURE
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(this, serviceIntent)

            captureScheduler.setServiceRunning(true)
            updateServiceStatusUI()
            updateStatus("✅ Screen capture started")
            Toast.makeText(this, "Screen capture started", Toast.LENGTH_SHORT).show()
        } else {
            Timber.w("MediaProjection denied or cancelled")
            updateStatus("❌ Screen capture permission denied")
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_LONG).show()
        }
    }

    private fun signInToGoogle() {
        val intent = authManager.getSignInIntent()
        if (intent != null) {
            signInLauncher.launch(intent)
        } else {
            Toast.makeText(this, "Failed to create sign-in intent", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleSignInResult(resultCode: Int, data: Intent?) {
        lifecycleScope.launch(Dispatchers.IO) {
            val account = authManager.handleSignInResult(data)
            runOnUiThread {
                if (account != null) {
                    btnSignIn.text = "Signed In ✓"
                    btnSignIn.isEnabled = false
                    updateStatus("✅ Signed in as ${account.email}")
                    Toast.makeText(this@MainActivity, "Signed in successfully", Toast.LENGTH_SHORT).show()
                } else {
                    updateStatus("❌ Sign in failed")
                    Toast.makeText(this@MainActivity, "Sign in failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun testSheetsConnection() {
        updateStatus("🔄 Testing Google Sheets connection...")
        btnTestConnection.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val repo = getSheetsRepository()
            if (repo != null) {
                val result = repo.testConnection()
                runOnUiThread {
                    btnTestConnection.isEnabled = true
                    if (result.isSuccess) {
                        updateStatus("✅ Google Sheets connection successful")
                        Toast.makeText(this@MainActivity, "Connection successful!", Toast.LENGTH_SHORT).show()
                    } else {
                        updateStatus("❌ Connection failed: ${result.exceptionOrNull()?.message}")
                        Toast.makeText(this@MainActivity, "Connection failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                runOnUiThread {
                    btnTestConnection.isEnabled = true
                    updateStatus("❌ Not authenticated - please sign in first")
                    Toast.makeText(this@MainActivity, "Please sign in to Google first", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun stopCaptureService() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP_CAPTURE
        }
        ContextCompat.startForegroundService(this, intent)
        captureScheduler.setServiceRunning(false)
        updateServiceStatusUI()
        updateStatus("🛑 Screen capture stopped")
        Toast.makeText(this, "Screen capture stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateServiceStatusUI() {
        val isRunning = captureScheduler.isServiceRunning()

        btnStartCapture.isEnabled = !isRunning
        btnStopCapture.isEnabled = isRunning

        if (isRunning) {
            tvStatus.text = "Status: Running (capturing screen)"
        } else {
            tvStatus.text = "Status: Stopped"
        }
    }

    private fun updateStatus(message: String) {
        tvStatus.text = message
        Timber.d("Status: $message")
    }

    private fun getSheetsRepository(): SheetsRepository? {
        try {
            val app = applicationContext as? PocetApplication
            return app?.let { hiltApp ->
                // Use Hilt to get repository
                // hiltApp.hiltComponent.sheetsRepository()
                null // Placeholder - will be implemented with Hilt
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get SheetsRepository")
            return null
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatusUI()
        checkSignInStatus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CODE_PERMISSIONS) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_LONG).show()
            }
        }
    }
}