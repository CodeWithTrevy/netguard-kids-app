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

        // How often to collect stats (1 minute for more accurate tracking)
        private const val COLLECTION_INTERVAL = 60 * 1000L
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private var isTracking = true
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Map to store running totals of app usage between uploads
    private val appUsageTotals = mutableMapOf<String, Long>()

    // Track when we last uploaded data
    private var lastUploadTime = 0L

    // Track when we last collected data
    private var lastQueryTime = 0L

    // Track currently running foreground app
    private var currentForegroundApp: String? = null
    private var currentForegroundStartTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate called")

        try {
            FirebaseApp.initializeApp(this)
            auth = FirebaseAuth.getInstance()
            db = FirebaseFirestore.getInstance()
            Log.d(TAG, "Firebase initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Firebase initialization error: ${e.message}")
        }

        // Initialize the last query time
        lastQueryTime = System.currentTimeMillis()

        // Authenticate immediately when service starts
        ensureAuthenticated()
        startForegroundService()
    }

    private fun ensureAuthenticated() {
        if (auth.currentUser == null) {
            Log.d(TAG, "Signing in anonymously at service start")
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d(TAG, "Anonymous auth successful at service start. UID: ${auth.currentUser?.uid}")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous auth failed at service start: ${e.message}")
                }
        } else {
            Log.d(TAG, "User already authenticated at service start. UID: ${auth.currentUser?.uid}")
        }
    }

    private fun startForegroundService() {
        Log.d(TAG, "Starting foreground service")

        createNotificationChannel()

        // Create a PendingIntent to launch the app when notification is tapped
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Child Monitoring Active")
            .setContentText("Tracking app usage in the background")
            .setSmallIcon(R.drawable.ic_monitoring) // Make sure you have this icon
            .setColor(Color.BLUE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
            Log.d(TAG, "Foreground service started successfully")
            trackUsagePeriodically()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground service: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "App Usage Tracking"
            val descriptionText = "Tracks app usage for parental control"
            val importance = NotificationManager.IMPORTANCE_HIGH

            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                lightColor = Color.BLUE
                setShowBadge(true)
            }

            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Notification channel created")
        }
    }

    private fun trackUsagePeriodically() {
        Log.d(TAG, "Starting periodic tracking")
        // Initialize last upload time
        lastUploadTime = System.currentTimeMillis()

        serviceScope.launch {
            while (isTracking) {
                try {
                    trackActiveAppUsage()

                    // Check if it's time to upload data
                    val currentTime = System.currentTimeMillis()
                    val timeSinceLastUpload = currentTime - lastUploadTime

                    Log.d(TAG, "Time since last upload attempt: ${timeSinceLastUpload/1000} seconds")

                    // Upload data every 2 minutes
                    if (timeSinceLastUpload >= 120000) {
                        Log.d(TAG, "Attempting to upload data after ${timeSinceLastUpload/1000} seconds")
                        authenticateAndUploadAllAppUsageData()
                        lastUploadTime = currentTime
                    }

                    // Check more frequently for accurate tracking (every 30 seconds)
                    delay(30000)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking loop: ${e.message}", e)
                    delay(60000) // Still delay on error to prevent rapid failure cycles
                }
            }
        }
    }

    private fun trackActiveAppUsage() {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usageStatsManager == null) {
                Log.e(TAG, "UsageStatsManager is null")
                return
            }

            val endTime = System.currentTimeMillis()
            val startTime = lastQueryTime // Track since last query

            // Use UsageEvents to get precise foreground app transitions
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            var activeApp: String? = currentForegroundApp
            var activeAppStartTime: Long = currentForegroundStartTime

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)

                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val packageName = event.packageName

                    // If we had an active app before, calculate its usage and add to totals
                    if (activeApp != null && shouldTrackApp(activeApp)) {
                        val usageTime = event.timeStamp - activeAppStartTime

                        if (usageTime > 0) {
                            val currentTotal = appUsageTotals.getOrDefault(activeApp, 0L)
                            appUsageTotals[activeApp] = currentTotal + usageTime
                            Log.d(TAG, "Tracked $activeApp usage: $usageTime ms")
                        }
                    }

                    // Update current active app
                    activeApp = packageName
                    activeAppStartTime = event.timeStamp
                }
                else if (event.eventType == UsageEvents.Event.MOVE_TO_BACKGROUND &&
                    event.packageName == activeApp) {
                    // App moved to background, calculate usage
                    if (activeApp != null && shouldTrackApp(activeApp)) {
                        val usageTime = event.timeStamp - activeAppStartTime

                        if (usageTime > 0) {
                            val currentTotal = appUsageTotals.getOrDefault(activeApp, 0L)
                            appUsageTotals[activeApp] = currentTotal + usageTime
                            Log.d(TAG, "Tracked $activeApp usage (to background): $usageTime ms")
                        }
                    }

                    // Clear active app since it's in background
                    activeApp = null
                    activeAppStartTime = 0L
                }
            }

            // If we still have an active app at the end of our processing,
            // update its time but don't finalize yet
            if (activeApp != null && shouldTrackApp(activeApp)) {
                // For the currently active app, calculate time until now
                val usageTime = endTime - activeAppStartTime

                // Update our tracking variables for next time
                currentForegroundApp = activeApp
                currentForegroundStartTime = activeAppStartTime

                Log.d(TAG, "Current active app: $activeApp (running for ${usageTime/1000} seconds)")
            } else {
                currentForegroundApp = null
                currentForegroundStartTime = 0L
            }

            // Update the query time for next run
            lastQueryTime = endTime

        } catch (e: Exception) {
            Log.e(TAG, "Error tracking active app usage: ${e.message}", e)
        }
    }

    private fun shouldTrackApp(packageName: String): Boolean {
        // Skip system UI, settings, and our own app
        return !packageName.equals(this.packageName) &&
                !packageName.startsWith("com.android.systemui") &&
                !packageName.startsWith("com.iconbiztechnologies1.childrenapp") &&
                !packageName.equals("com.android.settings") &&
                !packageName.startsWith("com.google.android.inputmethod") // Skip keyboard
    }

    private fun authenticateAndUploadAllAppUsageData() {
        if (appUsageTotals.isEmpty()) {
            Log.d(TAG, "No app usage data to upload")
            return
        }

        Log.d(TAG, "Preparing to upload usage data for ${appUsageTotals.size} apps")

        // Ensure user is authenticated before uploading
        val user = auth.currentUser
        if (user == null) {
            Log.d(TAG, "No authenticated user, signing in anonymously")
            auth.signInAnonymously()
                .addOnSuccessListener {
                    Log.d(TAG, "Anonymous auth successful. UID: ${auth.currentUser?.uid}")
                    uploadDataToFirestore(auth.currentUser!!.uid)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Anonymous auth failed: ${e.message}")
                    // Schedule retry after delay
                    serviceScope.launch {
                        delay(60000) // Wait a minute before trying again
                        authenticateAndUploadAllAppUsageData()
                    }
                }
        } else {
            Log.d(TAG, "User already authenticated for upload. UID: ${user.uid}")
            uploadDataToFirestore(user.uid)
        }
    }

    private fun uploadDataToFirestore(userId: String) {
        if (appUsageTotals.isEmpty()) {
            Log.d(TAG, "No data to upload after authentication")
            return
        }

        Log.d(TAG, "Starting upload to Firestore with user ID: $userId")
        Log.d(TAG, "We have ${appUsageTotals.size} apps to upload")

        // Get app labels where possible
        val packageManager = applicationContext.packageManager
        var successCount = 0
        var failureCount = 0

        // Create a copy to avoid ConcurrentModificationException
        val appsToUpload = HashMap(appUsageTotals)

        // Process each app's usage data individually instead of using batch
        for ((packageName, usageTime) in appsToUpload) {
            try {
                // Only upload if there's significant usage (e.g., more than 1 second)
                if (usageTime > 1000) {
                    // Try to get the app's display name
                    val appName = try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (e: Exception) {
                        packageName // Fall back to package name if we can't get label
                    }

                    val currentTimestamp = System.currentTimeMillis()
                    val currentDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                    // Create a HashMap directly
                    val appData = hashMapOf(
                        "package_name" to packageName,
                        "app_name" to appName,
                        "usage_time_ms" to usageTime,
                        "usage_time_minutes" to TimeUnit.MILLISECONDS.toMinutes(usageTime),
                        "child_id" to userId,
                        "timestamp" to currentTimestamp,
                        "date" to currentDate
                    )

                    Log.d(TAG, "Uploading app: $appName ($packageName) with ${usageTime}ms (${TimeUnit.MILLISECONDS.toMinutes(usageTime)} minutes)")

                    // Add document with direct method instead of using batch
                    db.collection("AppUsage")
                        .add(appData)
                        .addOnSuccessListener { documentReference ->
                            Log.d(TAG, "SUCCESS: Document added with ID: ${documentReference.id} for app: $appName")
                            successCount++
                            // Remove this entry from our tracking map once successfully uploaded
                            appUsageTotals.remove(packageName)
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "FAILURE: Error adding document for $appName: ${e.message}")
                            failureCount++
                        }

                } else {
                    // Remove insignificant usage
                    appUsageTotals.remove(packageName)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error preparing data for $packageName: ${e.message}")
                failureCount++
            }
        }

        Log.d(TAG, "Upload process initiated for ${appsToUpload.size} apps")
    }

    override fun onDestroy() {
        Log.d(TAG, "Service onDestroy called")
        // If we have an active app when service is being destroyed,
        // add its final usage time to our totals
        if (currentForegroundApp != null && shouldTrackApp(currentForegroundApp!!)) {
            val usageTime = System.currentTimeMillis() - currentForegroundStartTime
            if (usageTime > 0) {
                val currentTotal = appUsageTotals.getOrDefault(currentForegroundApp!!, 0L)
                appUsageTotals[currentForegroundApp!!] = currentTotal + usageTime

                // Try to upload one last time
                authenticateAndUploadAllAppUsageData()
            }
        }

        isTracking = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand called")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}