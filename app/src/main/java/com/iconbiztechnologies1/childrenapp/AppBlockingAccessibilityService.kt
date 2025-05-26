package com.iconbiztechnologies1.childrenapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.lang.ref.WeakReference

class AppBlockingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AppBlockingService"
        private const val PREFS_NAME = "AppBlockingPrefs"
        private const val KEY_IS_BLOCKING_ENABLED = "is_blocking_enabled"
        private const val KEY_RESET_TIME = "reset_time"
        private const val CHECK_INTERVAL = 30000L // Check every 30 seconds (more efficient)
        private const val BLOCK_COOLDOWN = 2000L // 2 second cooldown between blocks

        // Emergency apps that should never be blocked
        private val EMERGENCY_APPS = setOf(
            "com.android.dialer",
            "com.android.phone",
            "com.samsung.android.dialer",
            "com.google.android.dialer",
            "com.android.contacts",
            "com.sh.smart.caller",
            "com.android.emergency",
            "com.iconbiztechnologies1.childrenapp",
            "android"
        )

        // System apps that should not be blocked
        private val SYSTEM_APPS = setOf(
            "android",
            "com.android.systemui",
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher",
            "com.transsion.hilauncher", // HiOS Launcher
            "com.iconbiztechnologies1.childrenapp" // Our own app
        )

        // FIXED: Use WeakReference to prevent memory leaks
        @Volatile
        private var serviceInstance: WeakReference<AppBlockingAccessibilityService>? = null

        fun enableBlocking(context: Context, resetTime: String) {
            Log.d(TAG, "🔐 Attempting to enable blocking with reset time: $resetTime")

            // Method 1: Direct service call if available
            serviceInstance?.get()?.let { service ->
                Log.d(TAG, "✅ Service instance available, calling enableBlocking directly")
                service.enableBlocking(resetTime)
                return
            }

            // Method 2: Broadcast to service
            Log.d(TAG, "📡 Service instance not available, using broadcast")
            val intent = Intent("com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING").apply {
                putExtra("reset_time", resetTime)
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)

            // Method 3: SharedPreferences fallback
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_IS_BLOCKING_ENABLED, true)
                putString(KEY_RESET_TIME, resetTime)
                apply()
            }
            Log.d(TAG, "✅ Blocking enabled via SharedPreferences as fallback")
        }

        fun disableBlocking(context: Context) {
            Log.d(TAG, "🔓 Attempting to disable blocking")

            // Method 1: Direct service call if available
            serviceInstance?.get()?.let { service ->
                Log.d(TAG, "✅ Service instance available, calling disableBlocking directly")
                service.disableBlocking()
                return
            }

            // Method 2: Broadcast to service
            Log.d(TAG, "📡 Service instance not available, using broadcast")
            val intent = Intent("com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING")
            intent.setPackage(context.packageName)
            context.sendBroadcast(intent)

            // Method 3: SharedPreferences fallback
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean(KEY_IS_BLOCKING_ENABLED, false)
                remove(KEY_RESET_TIME) // Clear reset time when disabling
                apply()
            }
            Log.d(TAG, "✅ Blocking disabled via SharedPreferences as fallback")
        }

        fun isServiceRunning(): Boolean {
            val running = serviceInstance?.get() != null
            Log.d(TAG, "Service running status: $running")
            return running
        }
    }

    private lateinit var sharedPrefs: SharedPreferences
    private var isBlockingEnabled = false
    private var resetTime = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var resetCheckJob: Job? = null
    private var lastBlockedPackage = ""
    private var lastBlockTime = 0L
    private var isReceiverRegistered = false

    // FIXED: Add broadcast receiver for communication
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING" -> {
                    val resetTime = intent.getStringExtra("reset_time") ?: ""
                    Log.d(TAG, "📡 Received ENABLE_BLOCKING broadcast with reset time: $resetTime")
                    enableBlocking(resetTime)
                }
                "com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING" -> {
                    Log.d(TAG, "📡 Received DISABLE_BLOCKING broadcast")
                    disableBlocking()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // FIXED: Use WeakReference to prevent memory leaks
        serviceInstance = WeakReference(this)
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadBlockingState()
        registerBroadcastReceiver()
        startResetTimeChecker()
        Log.d(TAG, "AppBlockingAccessibilityService created and instance set")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
            packageNames = null // Monitor all packages
        }

        serviceInfo = info
        Log.d(TAG, "AppBlockingAccessibilityService connected with enhanced monitoring")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isBlockingEnabled || event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val packageName = event.packageName?.toString()
                if (packageName != null && shouldBlockApp(packageName)) {
                    // Prevent rapid blocking of the same app with longer cooldown
                    val currentTime = System.currentTimeMillis()
                    if (packageName != lastBlockedPackage || currentTime - lastBlockTime > BLOCK_COOLDOWN) {
                        Log.d(TAG, "Blocking app: $packageName")
                        blockApp(packageName)
                        lastBlockedPackage = packageName
                        lastBlockTime = currentTime
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "AppBlockingAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        serviceScope.cancel()
        resetCheckJob?.cancel()
        unregisterBroadcastReceiver()
        Log.d(TAG, "AppBlockingAccessibilityService destroyed")
    }

    // FIXED: Register broadcast receiver
    private fun registerBroadcastReceiver() {
        if (!isReceiverRegistered) {
            try {
                val filter = IntentFilter().apply {
                    addAction("com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING")
                    addAction("com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING")
                }
                registerReceiver(broadcastReceiver, filter)
                isReceiverRegistered = true
                Log.d(TAG, "Broadcast receiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Error registering broadcast receiver", e)
            }
        }
    }

    // FIXED: Unregister broadcast receiver
    private fun unregisterBroadcastReceiver() {
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(broadcastReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "Broadcast receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering broadcast receiver", e)
            }
        }
    }

    private fun shouldBlockApp(packageName: String): Boolean {
        // Don't block emergency apps
        if (EMERGENCY_APPS.contains(packageName)) {
            return false
        }

        // Don't block system apps
        if (SYSTEM_APPS.contains(packageName)) {
            return false
        }

        // Don't block launcher apps
        if (isLauncherApp(packageName)) {
            return false
        }

        // Don't block settings apps
        if (packageName.contains("settings", ignoreCase = true)) {
            return false
        }

        Log.d(TAG, "Should block app: $packageName")
        return true
    }

    private fun isLauncherApp(packageName: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfos = packageManager.queryIntentActivities(intent, 0)
            resolveInfos.any { it.activityInfo.packageName == packageName }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking if app is launcher: $packageName", e)
            false
        }
    }

    private fun blockApp(packageName: String) {
        try {
            Log.d(TAG, "Executing app block for: $packageName")

            // Show blocking overlay first
            showBlockingOverlay()

            // Small delay to ensure overlay is shown, then go to home
            serviceScope.launch {
                delay(300) // Slightly longer delay for better UX
                goToHomeScreen()

                // Show toast message after a brief delay
                delay(500)
                Toast.makeText(
                    this@AppBlockingAccessibilityService,
                    "Screen time limit reached! App access blocked until $resetTime",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error blocking app: $packageName", e)
        }
    }

    private fun showBlockingOverlay() {
        try {
            val intent = Intent(this, AppBlockedActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY) // Prevent back navigation
            }
            startActivity(intent)
            Log.d(TAG, "Blocking overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing blocking overlay", e)
        }
    }

    private fun goToHomeScreen() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }
            startActivity(intent)
            Log.d(TAG, "Navigated to home screen")
        } catch (e: Exception) {
            Log.e(TAG, "Error going to home screen", e)
        }
    }

    fun enableBlocking(resetTime: String) {
        // FIXED: Validate reset time format
        if (!isValidTimeFormat(resetTime)) {
            Log.e(TAG, "Invalid time format: $resetTime")
            Toast.makeText(this, "Invalid time format. Please use HH:MM format.", Toast.LENGTH_LONG).show()
            return
        }

        isBlockingEnabled = true
        this.resetTime = resetTime
        saveBlockingState()
        Log.d(TAG, "App blocking enabled. Reset time: $resetTime")

        // Show confirmation
        Toast.makeText(
            this,
            "Screen time limit activated. Apps will be blocked until $resetTime",
            Toast.LENGTH_LONG
        ).show()
    }

    fun disableBlocking() {
        isBlockingEnabled = false
        resetTime = ""
        saveBlockingState()
        Log.d(TAG, "App blocking disabled")

        // Show confirmation
        Toast.makeText(
            this,
            "Screen time limit reset. Apps are now available.",
            Toast.LENGTH_LONG
        ).show()
    }

    // FIXED: Add time format validation
    private fun isValidTimeFormat(time: String): Boolean {
        return try {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            format.isLenient = false
            format.parse(time)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun loadBlockingState() {
        isBlockingEnabled = sharedPrefs.getBoolean(KEY_IS_BLOCKING_ENABLED, false)
        resetTime = sharedPrefs.getString(KEY_RESET_TIME, "") ?: ""
        Log.d(TAG, "Loaded blocking state - Enabled: $isBlockingEnabled, Reset time: $resetTime")
    }

    private fun saveBlockingState() {
        sharedPrefs.edit().apply {
            putBoolean(KEY_IS_BLOCKING_ENABLED, isBlockingEnabled)
            if (resetTime.isNotEmpty()) {
                putString(KEY_RESET_TIME, resetTime)
            } else {
                remove(KEY_RESET_TIME)
            }
            apply()
        }
        Log.d(TAG, "Saved blocking state - Enabled: $isBlockingEnabled, Reset time: $resetTime")
    }

    private fun startResetTimeChecker() {
        resetCheckJob?.cancel() // Cancel existing job if any
        resetCheckJob = serviceScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL)
                if (isBlockingEnabled) {
                    checkResetTime()
                }
            }
        }
        Log.d(TAG, "Reset time checker started")
    }

    // FIXED: Improved reset time checking logic
    private fun checkResetTime() {
        if (!isBlockingEnabled || resetTime.isEmpty()) return

        try {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val currentTime = format.format(Date())
            val currentCalendar = Calendar.getInstance()
            val resetCalendar = Calendar.getInstance()

            // Parse reset time
            val resetDate = format.parse(resetTime)
            resetCalendar.time = resetDate ?: return

            // Set reset time to today
            resetCalendar.set(Calendar.YEAR, currentCalendar.get(Calendar.YEAR))
            resetCalendar.set(Calendar.MONTH, currentCalendar.get(Calendar.MONTH))
            resetCalendar.set(Calendar.DAY_OF_MONTH, currentCalendar.get(Calendar.DAY_OF_MONTH))

            // Check if we've passed the reset time (within a 1-minute window)
            val timeDiff = currentCalendar.timeInMillis - resetCalendar.timeInMillis
            if (timeDiff >= 0 && timeDiff < 60000) { // Within 1 minute
                Log.d(TAG, "Reset time reached: $resetTime")
                disableBlocking()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking reset time", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                "ENABLE_BLOCKING" -> {
                    val resetTime = it.getStringExtra("reset_time") ?: ""
                    enableBlocking(resetTime)
                }
                "DISABLE_BLOCKING" -> {
                    disableBlocking()
                }
            }
        }
        return START_STICKY
    }
}