package com.iconbiztechnologies1.childrenapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.lang.ref.WeakReference

class UnifiedAppBlockingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UnifiedAppBlockingService"
        private const val PREFS_NAME = "AppBlockingPrefs"
        private const val KEY_IS_BLOCKING_ENABLED = "is_blocking_enabled"
        private const val KEY_RESET_TIME = "reset_time"
        private const val CHECK_INTERVAL = 30000L // Check every 30 seconds
        private const val BLOCK_COOLDOWN = 2000L // 2 second cooldown between blocks
        private const val BLOCKED_APPS_COLLECTION = "blocked_apps"

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

        // Use WeakReference to prevent memory leaks
        @Volatile
        private var serviceInstance: WeakReference<UnifiedAppBlockingAccessibilityService>? = null

        fun enableBlocking(context: Context, resetTime: String) {
            Log.d(TAG, "🔐 Attempting to enable blocking with reset time: $resetTime")

            // Method 1: Direct service call if available
            serviceInstance?.get()?.let { service ->
                Log.d(TAG, "✅ Service instance available, calling enableBlocking directly")
                service.enableTimeBasedBlocking(resetTime)
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
                service.disableTimeBasedBlocking()
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

    // Firebase-related properties (from first service)
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var blockedAppsListener: ListenerRegistration? = null
    private val blockedApps = mutableSetOf<String>()

    // Time-based blocking properties (from second service)
    private lateinit var sharedPrefs: SharedPreferences
    private var isTimeBasedBlockingEnabled = false
    private var resetTime = ""
    private var resetCheckJob: Job? = null
    private var isReceiverRegistered = false

    // Common properties
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentForegroundApp: String? = null
    private var lastBlockedPackage = ""
    private var lastBlockTime = 0L

    // Broadcast receiver for communication
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING" -> {
                    val resetTime = intent.getStringExtra("reset_time") ?: ""
                    Log.d(TAG, "📡 Received ENABLE_BLOCKING broadcast with reset time: $resetTime")
                    enableTimeBasedBlocking(resetTime)
                }
                "com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING" -> {
                    Log.d(TAG, "📡 Received DISABLE_BLOCKING broadcast")
                    disableTimeBasedBlocking()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = WeakReference(this)
        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Initialize both blocking systems
        setupFirebaseBlockedAppsListener()
        loadTimeBasedBlockingState()
        registerBroadcastReceiver()
        startResetTimeChecker()

        Log.d(TAG, "UnifiedAppBlockingAccessibilityService created")
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
        Log.d(TAG, "Unified accessibility service connected")
        setupFirebaseBlockedAppsListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance = null
        blockedAppsListener?.remove()
        serviceScope.cancel()
        resetCheckJob?.cancel()
        unregisterBroadcastReceiver()
        Log.d(TAG, "UnifiedAppBlockingAccessibilityService destroyed")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Unified accessibility service interrupted")
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()

        if (packageName != null && packageName != currentForegroundApp) {
            currentForegroundApp = packageName
            Log.d(TAG, "App in foreground: $packageName")

            // Check both blocking systems
            val shouldBlockByFirebase = blockedApps.contains(packageName)
            val shouldBlockByTime = isTimeBasedBlockingEnabled && shouldBlockAppByTime(packageName)

            if (shouldBlockByFirebase || shouldBlockByTime) {
                // Prevent rapid blocking of the same app
                val currentTime = System.currentTimeMillis()
                if (packageName != lastBlockedPackage || currentTime - lastBlockTime > BLOCK_COOLDOWN) {
                    if (shouldBlockByFirebase) {
                        Log.d(TAG, "Firebase blocked app detected: $packageName")
                        blockAppFirebaseStyle(packageName)
                    } else if (shouldBlockByTime) {
                        Log.d(TAG, "Time-based blocked app detected: $packageName")
                        blockAppTimeBasedStyle(packageName)
                    }
                    lastBlockedPackage = packageName
                    lastBlockTime = currentTime
                }
            }
        }
    }

    // ==================== FIREBASE BLOCKING LOGIC ====================

    private fun setupFirebaseBlockedAppsListener() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "User not authenticated for Firebase blocking")
            return
        }

        Log.d(TAG, "Setting up Firebase blocked apps listener for user: $userId")

        blockedAppsListener?.remove() // Remove existing listener if any

        blockedAppsListener = firestore.collection(BLOCKED_APPS_COLLECTION)
            .document("parent_blocked_apps")
            .collection(userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to blocked apps", error)
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val newBlockedApps = mutableSetOf<String>()

                    for (document in snapshots.documents) {
                        newBlockedApps.add(document.id)
                    }

                    val previousSize = blockedApps.size
                    blockedApps.clear()
                    blockedApps.addAll(newBlockedApps)

                    Log.d(TAG, "Updated Firebase blocked apps list: ${blockedApps.size} apps blocked")
                    Log.d(TAG, "Firebase blocked apps: ${blockedApps.joinToString(", ")}")

                    // Log changes for debugging
                    if (blockedApps.size != previousSize) {
                        Log.d(TAG, "Firebase blocked apps count changed from $previousSize to ${blockedApps.size}")
                    }
                }
            }
    }

    private fun blockAppFirebaseStyle(packageName: String) {
        try {
            val appName = getAppName(packageName)

            // Launch the blocking activity (original Firebase style)
            val intent = Intent(this, AppBlockedActivity1::class.java).apply {
                putExtra("blocked_package", packageName)
                putExtra("blocked_app_name", appName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }

            startActivity(intent)
            Log.d(TAG, "Launched Firebase-style blocking activity for: $packageName")

        } catch (e: Exception) {
            Log.e(TAG, "Error blocking app Firebase-style: $packageName", e)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val packageManager = packageManager
            val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    // ==================== TIME-BASED BLOCKING LOGIC ====================

    private fun shouldBlockAppByTime(packageName: String): Boolean {
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

        Log.d(TAG, "Should block app by time: $packageName")
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

    private fun blockAppTimeBasedStyle(packageName: String) {
        try {
            Log.d(TAG, "Executing time-based app block for: $packageName")

            // Show blocking overlay first
            showTimeBasedBlockingOverlay()

            // Small delay to ensure overlay is shown, then go to home
            serviceScope.launch {
                delay(300)
                goToHomeScreen()

                // Show toast message after a brief delay
                delay(500)
                Toast.makeText(
                    this@UnifiedAppBlockingAccessibilityService,
                    "Screen time limit reached! App access blocked until $resetTime",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error blocking app time-based style: $packageName", e)
        }
    }

    private fun showTimeBasedBlockingOverlay() {
        try {
            val intent = Intent(this, AppBlockedActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_HISTORY) // Prevent back navigation
            }
            startActivity(intent)
            Log.d(TAG, "Time-based blocking overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing time-based blocking overlay", e)
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

    fun enableTimeBasedBlocking(resetTime: String) {
        // Validate reset time format
        if (!isValidTimeFormat(resetTime)) {
            Log.e(TAG, "Invalid time format: $resetTime")
            Toast.makeText(this, "Invalid time format. Please use HH:MM format.", Toast.LENGTH_LONG).show()
            return
        }

        isTimeBasedBlockingEnabled = true
        this.resetTime = resetTime
        saveTimeBasedBlockingState()
        Log.d(TAG, "Time-based app blocking enabled. Reset time: $resetTime")

        // Show confirmation
        Toast.makeText(
            this,
            "Screen time limit activated. Apps will be blocked until $resetTime",
            Toast.LENGTH_LONG
        ).show()
    }

    fun disableTimeBasedBlocking() {
        isTimeBasedBlockingEnabled = false
        resetTime = ""
        saveTimeBasedBlockingState()
        Log.d(TAG, "Time-based app blocking disabled")

        // Show confirmation
        Toast.makeText(
            this,
            "Screen time limit reset. Apps are now available.",
            Toast.LENGTH_LONG
        ).show()
    }

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

    private fun loadTimeBasedBlockingState() {
        isTimeBasedBlockingEnabled = sharedPrefs.getBoolean(KEY_IS_BLOCKING_ENABLED, false)
        resetTime = sharedPrefs.getString(KEY_RESET_TIME, "") ?: ""
        Log.d(TAG, "Loaded time-based blocking state - Enabled: $isTimeBasedBlockingEnabled, Reset time: $resetTime")
    }

    private fun saveTimeBasedBlockingState() {
        sharedPrefs.edit().apply {
            putBoolean(KEY_IS_BLOCKING_ENABLED, isTimeBasedBlockingEnabled)
            if (resetTime.isNotEmpty()) {
                putString(KEY_RESET_TIME, resetTime)
            } else {
                remove(KEY_RESET_TIME)
            }
            apply()
        }
        Log.d(TAG, "Saved time-based blocking state - Enabled: $isTimeBasedBlockingEnabled, Reset time: $resetTime")
    }

    private fun startResetTimeChecker() {
        resetCheckJob?.cancel() // Cancel existing job if any
        resetCheckJob = serviceScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL)
                if (isTimeBasedBlockingEnabled) {
                    checkResetTime()
                }
            }
        }
        Log.d(TAG, "Reset time checker started")
    }

    private fun checkResetTime() {
        if (!isTimeBasedBlockingEnabled || resetTime.isEmpty()) return

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
                disableTimeBasedBlocking()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking reset time", e)
        }
    }



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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                "ENABLE_BLOCKING" -> {
                    val resetTime = it.getStringExtra("reset_time") ?: ""
                    enableTimeBasedBlocking(resetTime)
                }
                "DISABLE_BLOCKING" -> {
                    disableTimeBasedBlocking()
                }
            }
        }
        return START_STICKY
    }
}