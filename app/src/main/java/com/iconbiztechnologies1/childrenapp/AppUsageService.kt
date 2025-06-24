// File: AppUsageService.kt
package com.iconbiztechnologies1.childrenapp

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.*
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AppUsageService : Service() {
    companion object {
        private const val TAG = "AppUsageService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "app_usage_channel"
        private const val UPLOAD_INTERVAL = 2 * 60 * 1000L // 2 minutes
        private const val TRACKING_INTERVAL = 30 * 1000L // 30 seconds
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var isTracking = true
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val appUsageTotals = mutableMapOf<String, Long>()
    private var lastUploadTime = 0L
    private var lastQueryTime = 0L
    private var currentForegroundApp: String? = null
    private var currentForegroundStartTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")
        try {
            FirebaseApp.initializeApp(this)
            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization error: ${e.message}")
        }
        lastQueryTime = System.currentTimeMillis()
        ensureAuthenticated()
        startForegroundService()
    }

    private fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { Log.d(TAG, "Anonymous auth successful. UID: ${auth.currentUser?.uid}") }
                .addOnFailureListener { e -> Log.e(TAG, "Anonymous auth failed: ${e.message}") }
        }
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val pendingIntent = PendingIntent.getActivity(this, 0, packageManager.getLaunchIntentForPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Child Monitoring Active")
            .setContentText("Tracking app usage in the background")
            .setSmallIcon(R.drawable.ic_monitoring)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            startForeground(NOTIFICATION_ID, notification)
            trackUsagePeriodically()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "App Usage Tracking", NotificationManager.IMPORTANCE_HIGH)
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun trackUsagePeriodically() {
        lastUploadTime = System.currentTimeMillis()
        serviceScope.launch {
            while (isTracking) {
                try {
                    trackActiveAppUsage()
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUploadTime >= UPLOAD_INTERVAL) {
                        authenticateAndUploadAllAppUsageData()
                        lastUploadTime = currentTime
                    }
                    delay(TRACKING_INTERVAL)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking loop: ${e.message}", e)
                    delay(60000)
                }
            }
        }
    }

    private fun trackActiveAppUsage() {
        val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val endTime = System.currentTimeMillis()
        val startTime = lastQueryTime
        val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var activeApp = currentForegroundApp
        var activeAppStartTime = currentForegroundStartTime

        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                if (activeApp != null && shouldTrackApp(activeApp)) {
                    val usageTime = event.timeStamp - activeAppStartTime
                    if (usageTime > 0) appUsageTotals[activeApp] = appUsageTotals.getOrDefault(activeApp, 0L) + usageTime
                }
                activeApp = event.packageName
                activeAppStartTime = event.timeStamp
            } else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND && event.packageName == activeApp) {
                if (activeApp != null && shouldTrackApp(activeApp)) {
                    val usageTime = event.timeStamp - activeAppStartTime
                    if (usageTime > 0) appUsageTotals[activeApp] = appUsageTotals.getOrDefault(activeApp, 0L) + usageTime
                }
                activeApp = null
                activeAppStartTime = 0L
            }
        }
        currentForegroundApp = activeApp
        currentForegroundStartTime = activeAppStartTime
        lastQueryTime = endTime
    }

    private fun shouldTrackApp(packageName: String): Boolean {
        return packageName != this.packageName &&
                !packageName.startsWith("com.android.systemui") &&
                !packageName.equals("com.android.settings") &&
                !packageName.startsWith("com.google.android.inputmethod")
    }

    private fun authenticateAndUploadAllAppUsageData() {
        if (appUsageTotals.isEmpty()) return
        val user = auth.currentUser
        if (user == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { uploadDataToFirestore(it.user!!.uid) }
                .addOnFailureListener { e -> Log.e(TAG, "Anonymous auth failed: ${e.message}") }
        } else {
            uploadDataToFirestore(user.uid)
        }
    }

    private fun uploadDataToFirestore(parentUserId: String) {
        val physicalDeviceId = DeviceIdentityManager.getDeviceID(this)
        if (physicalDeviceId == null) {
            Log.e(TAG, "CRITICAL: Physical Device ID not found. Cannot upload usage data.")
            appUsageTotals.clear()
            return
        }
        if (appUsageTotals.isEmpty()) return

        Log.d(TAG, "Starting upload to Firestore for physical device ID: $physicalDeviceId")
        val packageManager = applicationContext.packageManager
        val appsToUpload = HashMap(appUsageTotals)
        appUsageTotals.clear() // Clear immediately to prevent race conditions with the tracking loop

        for ((packageName, usageTime) in appsToUpload) {
            if (usageTime > 1000) {
                val appName = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
                } catch (e: Exception) {
                    packageName
                }
                val appData = hashMapOf(
                    "package_name" to packageName,
                    "app_name" to appName,
                    "usage_time_ms" to usageTime,
                    "usage_time_minutes" to TimeUnit.MILLISECONDS.toMinutes(usageTime),
                    "physical_device_id" to physicalDeviceId, // <-- THE KEY CHANGE
                    "parent_user_id" to parentUserId, // <-- Storing parent ID for reference
                    "timestamp" to System.currentTimeMillis(),
                    "date" to SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                )
                db.collection("AppUsage").add(appData)
                    .addOnFailureListener { e -> Log.e(TAG, "FAILURE: Error adding document for $appName: ${e.message}") }
            }
        }
    }

    override fun onDestroy() {
        isTracking = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
}
