package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.widget.TextView
import android.widget.ProgressBar
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import android.provider.Settings
import androidx.appcompat.app.AlertDialog
import kotlin.coroutines.suspendCoroutine

class DailyUsageActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DailyUsageActivity"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var totalTimeText: TextView
    private lateinit var totalTimeSubtext: TextView
    private lateinit var circularProgress: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyStateView: View

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var appUsageAdapter: AppUsageAdapter

    private val appUsageList = mutableListOf<AppUsageItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_usage)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // Initialize views
        initViews()
        setupRecyclerView()
        setupSwipeRefresh()

        // Load today's screen time data
        loadTodaysScreenTime()
    }

    private fun initViews() {
        totalTimeText = findViewById(R.id.totalTimeText)
        totalTimeSubtext = findViewById(R.id.totalTimeSubtext)
        circularProgress = findViewById(R.id.circularProgress)
        recyclerView = findViewById(R.id.appUsageRecyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        emptyStateView = findViewById(R.id.emptyStateView)

        // Setup toolbar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Daily App Usage"
    }

    private fun setupRecyclerView() {
        appUsageAdapter = AppUsageAdapter(appUsageList)
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@DailyUsageActivity)
            adapter = appUsageAdapter
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            loadTodaysScreenTime()
        }
        swipeRefresh.setColorSchemeResources(
            R.color.colorPrimary,
            R.color.colorPrimaryDark,
            R.color.colorAccent
        )
    }

    private fun loadTodaysScreenTime() {
        val user = auth.currentUser
        if (user == null) {
            Log.e(TAG, "No authenticated user")
            swipeRefresh.isRefreshing = false
            return
        }

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        Log.d(TAG, "Loading screen time for date: $today and user: ${user.uid}")

        swipeRefresh.isRefreshing = true

        activityScope.launch {
            try {
                val appUsageData = withContext(Dispatchers.IO) {
                    fetchTodaysAppUsage(user.uid, today)
                }

                val screenTimeSettings = withContext(Dispatchers.IO) {
                    fetchScreenTimeSettings(user.uid)
                }

                updateUI(appUsageData, screenTimeSettings)

                // Always update the used screen time in database with current total usage
                val totalTimeMs = appUsageData.values.sum()
                val totalUsedMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs).toInt()
                if (totalUsedMinutes > 0) {
                    Log.d(TAG, "Updating database with current total usage: $totalUsedMinutes minutes")
                    updateUsedScreenTime(user.uid, totalUsedMinutes)
                }

                // Check if screen time limit is exceeded - FIXED: Do this after database update
                checkScreenTimeLimit(appUsageData, screenTimeSettings)

                swipeRefresh.isRefreshing = false
            } catch (e: Exception) {
                Log.e(TAG, "Error loading screen time data", e)
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private suspend fun fetchTodaysAppUsage(userId: String, date: String): Map<String, Long> {
        return suspendCancellableCoroutine { continuation ->
            db.collection("AppUsage")
                .whereEqualTo("child_id", userId)
                .whereEqualTo("date", date)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .addOnSuccessListener { documents ->
                    val appUsageMap = mutableMapOf<String, Long>()

                    for (document in documents) {
                        val packageName = document.getString("package_name") ?: continue
                        val usageTimeMs = document.getLong("usage_time_ms") ?: 0L

                        // Accumulate usage time for each app
                        appUsageMap[packageName] = appUsageMap.getOrDefault(packageName, 0L) + usageTimeMs
                    }

                    Log.d(TAG, "Fetched usage data for ${appUsageMap.size} apps")
                    continuation.resume(appUsageMap) { }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Error fetching app usage data", exception)
                    continuation.resume(emptyMap()) { }
                }
        }
    }

    private suspend fun fetchScreenTimeSettings(userId: String): ScreenTimeSettings? {
        return suspendCancellableCoroutine { continuation ->
            db.collection("ScreenTime")
                .whereEqualTo("user_id", userId)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .limit(1)
                .get()
                .addOnSuccessListener { documents ->
                    if (!documents.isEmpty) {
                        val document = documents.documents[0]
                        val settings = ScreenTimeSettings(
                            userId = userId,
                            screenTimeHours = document.getLong("screen_time_hours")?.toInt() ?: 0,
                            screenTimeMinutes = document.getLong("screen_time_minutes")?.toInt() ?: 0,
                            screenTimeTotalMinutes = document.getLong("screen_time_total_minutes")?.toInt() ?: 0,
                            resetScreenTime = document.getString("reset_screen_time") ?: "",
                            screenTimeUsedHours = document.getLong("screen_time_used_hours")?.toInt() ?: 0,
                            screenTimeUsedMinutes = document.getLong("screen_time_used_minutes")?.toInt() ?: 0
                        )
                        Log.d(TAG, "Fetched screen time settings: $settings")
                        continuation.resume(settings) { }
                    } else {
                        Log.d(TAG, "No screen time settings found")
                        continuation.resume(null) { }
                    }
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Error fetching screen time settings", exception)
                    continuation.resume(null) { }
                }
        }
    }

    private fun updateUI(appUsageData: Map<String, Long>, screenTimeSettings: ScreenTimeSettings?) {
        if (appUsageData.isEmpty()) {
            showEmptyState()
            return
        }

        // Calculate total screen time
        val totalTimeMs = appUsageData.values.sum()
        val totalHours = TimeUnit.MILLISECONDS.toHours(totalTimeMs)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs) % 60

        // Update total time display
        if (totalHours > 0) {
            totalTimeText.text = "${totalHours}h ${totalMinutes}m"
        } else {
            totalTimeText.text = "${totalMinutes}m"
        }

        // Update subtext with limit information
        if (screenTimeSettings != null) {
            val limitHours = screenTimeSettings.screenTimeHours
            val limitMinutes = screenTimeSettings.screenTimeMinutes
            val limitText = if (limitHours > 0) {
                "${limitHours}h ${limitMinutes}m"
            } else {
                "${limitMinutes}m"
            }
            totalTimeSubtext.text = "Total screen time today (Limit: $limitText)"

            // Update circular progress based on limit
            val limitTotalMinutes = screenTimeSettings.screenTimeTotalMinutes
            val usedTotalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs).toInt()

            if (limitTotalMinutes > 0) {
                val progressPercentage = ((usedTotalMinutes.toFloat() / limitTotalMinutes) * 100).coerceAtMost(100f)
                circularProgress.progress = progressPercentage.toInt()

                // Change color if limit exceeded
                if (usedTotalMinutes >= limitTotalMinutes) {
                    circularProgress.progressDrawable.setColorFilter(
                        ContextCompat.getColor(this, android.R.color.holo_red_dark),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                } else {
                    circularProgress.progressDrawable.setColorFilter(
                        ContextCompat.getColor(this, R.color.colorPrimary),
                        android.graphics.PorterDuff.Mode.SRC_IN
                    )
                }
            }
        } else {
            totalTimeSubtext.text = "Total screen time today"
            // Update circular progress (assuming max of 12 hours = 100%)
            val maxTimeMs = TimeUnit.HOURS.toMillis(12)
            val progressPercentage = ((totalTimeMs.toFloat() / maxTimeMs) * 100).coerceAtMost(100f)
            circularProgress.progress = progressPercentage.toInt()
        }

        // Prepare app usage items for RecyclerView
        appUsageList.clear()

        activityScope.launch(Dispatchers.IO) {
            // Create simplified app representations from the database entries
            val items = appUsageData.entries
                .sortedByDescending { it.value }
                .map { (packageName, usageTimeMs) ->
                    var appName = extractAppNameFromPackage(packageName)
                    val usagePercentage = (usageTimeMs.toFloat() / totalTimeMs) * 100f
                    var appIcon: Drawable? = null

                    // Try to get app icon if installed
                    try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        appName = packageManager.getApplicationLabel(appInfo).toString()
                        appIcon = packageManager.getApplicationIcon(appInfo)
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.d(TAG, "App not found on device: $packageName, proceeding with extracted name")
                        // No need to set appIcon, it will remain null
                    }

                    AppUsageItem(
                        packageName = packageName,
                        appName = appName,
                        usageTimeMs = usageTimeMs,
                        appIcon = appIcon,
                        usagePercentage = usagePercentage
                    )
                }

            withContext(Dispatchers.Main) {
                appUsageList.addAll(items)
                appUsageAdapter.notifyDataSetChanged()

                // Show/hide views based on data
                recyclerView.visibility = View.VISIBLE
                emptyStateView.visibility = View.GONE
            }
        }
    }

    // FIXED: Improved checkScreenTimeLimit method
    private fun checkScreenTimeLimit(appUsageData: Map<String, Long>, screenTimeSettings: ScreenTimeSettings?) {
        if (screenTimeSettings == null) {
            Log.d(TAG, "No screen time settings found, skipping limit check")
            return
        }

        // Calculate total used time from app usage data
        val totalTimeMs = appUsageData.values.sum()
        val totalUsedMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs).toInt()
        val totalUsedHours = totalUsedMinutes / 60
        val limitHours = screenTimeSettings.screenTimeHours
        val limitTotalMinutes = screenTimeSettings.screenTimeTotalMinutes
        val resetTime = screenTimeSettings.resetScreenTime

        Log.d(TAG, "=== SCREEN TIME LIMIT CHECK ===")
        Log.d(TAG, "Total used: ${totalUsedMinutes} minutes (${totalUsedHours} hours)")
        Log.d(TAG, "Limit hours: $limitHours")
        Log.d(TAG, "Limit total minutes: $limitTotalMinutes")
        Log.d(TAG, "Reset time: $resetTime")

        // FIXED: Improved logic for determining if blocking should be enabled
        val shouldEnableBlocking = when {
            // Check if total used minutes exceeds the total limit set by parent
            limitTotalMinutes > 0 && totalUsedMinutes >= limitTotalMinutes -> {
                Log.d(TAG, "✅ LIMIT EXCEEDED - Total minutes: $totalUsedMinutes >= $limitTotalMinutes")
                true
            }
            // Fallback: Check hours if total minutes not set properly
            limitHours > 0 && totalUsedHours >= limitHours -> {
                Log.d(TAG, "✅ LIMIT EXCEEDED - Hours: $totalUsedHours >= $limitHours")
                true
            }
            else -> {
                Log.d(TAG, "❌ Within limit - Minutes: $totalUsedMinutes/$limitTotalMinutes, Hours: $totalUsedHours/$limitHours")
                false
            }
        }

        Log.d(TAG, "Should enable blocking: $shouldEnableBlocking")

        if (shouldEnableBlocking) {
            Log.d(TAG, "🚫 ENABLING APP BLOCKING - Screen time limit exceeded!")

            // FIXED: Update Firebase first to enable blocking
            updateFirebaseBlockingStatus(auth.currentUser?.uid ?: "", true, resetTime) {
                // After Firebase update, also call the accessibility service
                if (!UnifiedAppBlockingAccessibilityService.isServiceRunning()) {
                    Log.w(TAG, "⚠️ Accessibility service is not running! User needs to enable it in settings.")
                    // Show dialog to enable accessibility service
                    showAccessibilityServiceDialog()
                } else {
                    Log.d(TAG, "✅ Accessibility service is running, enabling blocking...")
                    UnifiedAppBlockingAccessibilityService.enableBlocking(this, resetTime)
                }
            }
        } else {
            Log.d(TAG, "✅ Screen time within limit, ensuring blocking is disabled")
            updateFirebaseBlockingStatus(auth.currentUser?.uid ?: "", false, resetTime) {
                UnifiedAppBlockingAccessibilityService.disableBlocking(this)
            }
        }
    }

    // NEW: Method to update Firebase blocking status
    private fun updateFirebaseBlockingStatus(userId: String, isBlocked: Boolean, resetTime: String, onComplete: () -> Unit) {
        if (userId.isEmpty()) {
            Log.w(TAG, "UserId is empty, cannot update blocking status")
            onComplete()
            return
        }

        Log.d(TAG, "=== UPDATING FIREBASE BLOCKING STATUS ===")
        Log.d(TAG, "User ID: $userId")
        Log.d(TAG, "Is Blocked: $isBlocked")
        Log.d(TAG, "Reset Time: $resetTime")

        val updateData = mapOf(
            "is_blocked" to isBlocked,
            "reset_screen_time" to resetTime,
            "blocking_updated_by" to "child_app",
            "blocking_updated_at" to System.currentTimeMillis(),
            "timestamp" to System.currentTimeMillis()
        )

        // Update all documents in ScreenTime collection where user_id matches
        db.collection("ScreenTime")
            .whereEqualTo("user_id", userId)
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Found ${documents.size()} ScreenTime documents for blocking update")

                if (!documents.isEmpty) {
                    val batch = db.batch()
                    var updateCount = 0

                    for (document in documents) {
                        batch.update(document.reference, updateData)
                        updateCount++
                        Log.d(TAG, "Added document ${document.id} to blocking batch update")
                    }

                    batch.commit()
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Successfully updated blocking status in $updateCount ScreenTime documents!")
                            onComplete()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Error updating blocking status in Firebase", e)
                            onComplete()
                        }
                } else {
                    Log.w(TAG, "⚠️ No ScreenTime documents found for blocking update")
                    onComplete()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Error querying ScreenTime collection for blocking update", e)
                onComplete()
            }
    }

    // NEW: Method to show accessibility service dialog
    private fun showAccessibilityServiceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Enable Accessibility Service")
            .setMessage("To enforce screen time limits, please enable the accessibility service in Settings > Accessibility > Children App.")
            .setPositiveButton("Open Settings") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e(TAG, "Error opening accessibility settings", e)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // DEBUGGING: Add this method for testing
    private fun testBlockingSystem() {
        Log.d(TAG, "=== TESTING BLOCKING SYSTEM ===")

        // Test accessibility service
        val isServiceRunning = UnifiedAppBlockingAccessibilityService.isServiceRunning()
        Log.d(TAG, "Accessibility service running: $isServiceRunning")

        // Test Firebase connectivity
        val user = auth.currentUser
        Log.d(TAG, "Current user: ${user?.uid}")

        // Test blocking call
        if (isServiceRunning) {
            UnifiedAppBlockingAccessibilityService.enableBlocking(this, "12:30")
            Log.d(TAG, "Sent enable blocking command")
        } else {
            Log.e(TAG, "Cannot test - accessibility service not running")
        }
    }
    private fun updateUsedScreenTime(userId: String, usedMinutes: Int, onComplete: (() -> Unit)? = null) {
        if (userId.isEmpty()) {
            Log.w(TAG, "UserId is empty, cannot update screen time")
            onComplete?.invoke()
            return
        }

        val usedHours = usedMinutes / 60
        val remainingMinutes = usedMinutes % 60

        Log.d(TAG, "=== UPDATING SCREEN TIME USAGE ===")
        Log.d(TAG, "User ID: $userId")
        Log.d(TAG, "Total minutes: $usedMinutes -> Hours: $usedHours, Minutes: $remainingMinutes")

        val updateData = mapOf(
            "screen_time_used_hours" to usedHours.toLong(),
            "screen_time_used_minutes" to remainingMinutes.toLong(),
            "screen_time_used_total_minutes" to usedMinutes.toLong(), // ADDED: Store total minutes used
            "last_updated_by" to "child_app",
            "last_updated_by_device" to android.os.Build.MODEL,
            "timestamp" to System.currentTimeMillis()
        )

        Log.d(TAG, "Update data: $updateData")

        // Update all documents in ScreenTime collection where user_id matches
        db.collection("ScreenTime")
            .whereEqualTo("user_id", userId)
            .get()
            .addOnSuccessListener { documents ->
                Log.d(TAG, "Found ${documents.size()} ScreenTime documents for user: $userId")

                if (!documents.isEmpty) {
                    // Update each matching document
                    val batch = db.batch()
                    var updateCount = 0

                    for (document in documents) {
                        batch.update(document.reference, updateData)
                        updateCount++
                        Log.d(TAG, "Added document ${document.id} to batch update")
                    }

                    // Commit the batch update
                    batch.commit()
                        .addOnSuccessListener {
                            Log.d(TAG, "✅ Successfully updated $updateCount ScreenTime documents!")
                            Log.d(TAG, "Updated values - Hours: $usedHours, Minutes: $remainingMinutes, Total: $usedMinutes")
                            onComplete?.invoke()
                        }
                        .addOnFailureListener { e ->
                            Log.e(TAG, "❌ Error executing batch update for ScreenTime documents", e)
                            onComplete?.invoke()
                        }
                } else {
                    Log.w(TAG, "⚠️ No ScreenTime documents found for user: $userId")
                    Log.w(TAG, "Make sure the parent app has created a ScreenTime record for this user")
                    onComplete?.invoke()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Error querying ScreenTime collection", e)
                Log.e(TAG, "Query: user_id == $userId")
                onComplete?.invoke()
            }
    }

    // Helper function to extract a readable app name from a package name
    private fun extractAppNameFromPackage(packageName: String): String {
        // Handle some common package names specifically
        return when (packageName) {
            "com.google.android.youtube" -> "YouTube"
            "com.whatsapp" -> "WhatsApp"
            "com.transsion.hilauncher" -> "HiOS Launcher"
            "com.sh.smart.caller" -> "Smart Caller"
            "com.facebook.katana" -> "Facebook"
            "com.instagram.android" -> "Instagram"
            "com.snapchat.android" -> "Snapchat"
            "com.twitter.android" -> "Twitter"
            "com.tencent.mm" -> "WeChat"
            "com.vkontakte.android" -> "VKontakte"
            "com.linkedin.android" -> "LinkedIn"
            "com.pinterest" -> "Pinterest"
            "com.reddit.frontpage" -> "Reddit"
            "com.spotify.music" -> "Spotify"
            "com.netflix.mediaclient" -> "Netflix"
            "com.discord" -> "Discord"
            "com.telegram.messenger" -> "Telegram"
            "com.viber.voip" -> "Viber"
            "com.skype.raider" -> "Skype"
            "com.zhiliaoapp.musically" -> "TikTok"
            "com.ss.android.ugc.trill" -> "TikTok Lite"
            "com.google.android.apps.photos" -> "Google Photos"
            "com.google.android.gm" -> "Gmail"
            "com.google.android.apps.maps" -> "Google Maps"
            "com.google.android.chrome" -> "Chrome"
            "com.mozilla.firefox" -> "Firefox"
            "com.opera.browser" -> "Opera"
            "com.microsoft.office.outlook" -> "Outlook"
            "com.microsoft.teams" -> "Microsoft Teams"
            "com.zoom.us" -> "Zoom"
            "us.zoom.videomeetings" -> "Zoom"
            "com.google.android.apps.docs" -> "Google Docs"
            "com.google.android.apps.sheets" -> "Google Sheets"
            "com.google.android.apps.slides" -> "Google Slides"
            "com.dropbox.android" -> "Dropbox"
            "com.google.android.apps.drive" -> "Google Drive"
            "com.microsoft.office.word" -> "Microsoft Word"
            "com.microsoft.office.excel" -> "Microsoft Excel"
            "com.microsoft.office.powerpoint" -> "Microsoft PowerPoint"
            "com.adobe.reader" -> "Adobe Reader"
            "com.amazon.mshop.android.shopping" -> "Amazon Shopping"
            "com.ebay.mobile" -> "eBay"
            "com.paypal.android.p2pmobile" -> "PayPal"
            "com.ubercab" -> "Uber"
            "com.lyft.android" -> "Lyft"
            "com.airbnb.android" -> "Airbnb"
            "com.booking" -> "Booking.com"
            "com.tripadvisor.tripadvisor" -> "TripAdvisor"
            "com.duolingo" -> "Duolingo"
            "com.khanacademy.android" -> "Khan Academy"
            "com.coursera.android" -> "Coursera"
            "com.udemy.android" -> "Udemy"
            "com.google.android.apps.fitness" -> "Google Fit"
            "com.myfitnesspal.android" -> "MyFitnessPal"
            "com.nike.plusone" -> "Nike Run Club"
            "com.strava" -> "Strava"
            "com.android.vending" -> "Google Play Store"
            "com.apple.android.music" -> "Apple Music"
            "com.pandora.android" -> "Pandora"
            "com.soundcloud.android" -> "SoundCloud"
            "com.shazam.android" -> "Shazam"
            "com.amazon.avod.thirdpartyclient" -> "Amazon Prime Video"
            "com.hulu.plus" -> "Hulu"
            "com.disney.disneyplus" -> "Disney+"
            "com.hbo.hbonow" -> "HBO Max"
            "com.google.android.youtube.tv" -> "YouTube TV"
            "com.twitch.android.app" -> "Twitch"
            "com.android.chrome" -> "Chrome"
            "com.sec.android.app.sbrowser" -> "Samsung Internet"
            "com.mi.globalbrowser" -> "Mi Browser"
            "com.UCMobile.intl" -> "UC Browser"
            "com.brave.browser" -> "Brave Browser"
            else -> {
                // Extract app name from package name by taking the last part and capitalizing
                val parts = packageName.split(".")
                val lastPart = parts.lastOrNull() ?: packageName

                // Remove common suffixes and format nicely
                val cleanName = lastPart
                    .replace("android", "", ignoreCase = true)
                    .replace("app", "", ignoreCase = true)
                    .replace("mobile", "", ignoreCase = true)
                    .trim()

                if (cleanName.isNotEmpty()) {
                    cleanName.replaceFirstChar {
                        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
                    }
                } else {
                    packageName
                }
            }
        }
    }

    private fun showEmptyState() {
        recyclerView.visibility = View.GONE
        emptyStateView.visibility = View.VISIBLE
        totalTimeText.text = "0m"
        totalTimeSubtext.text = "No screen time data for today"
        circularProgress.progress = 0
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}

// Data classes - moved to separate file to avoid redeclaration
data class ScreenTimeSettings(
    val userId: String,
    val screenTimeHours: Int,
    val screenTimeMinutes: Int,
    val screenTimeTotalMinutes: Int,
    val resetScreenTime: String,
    val screenTimeUsedHours: Int,
    val screenTimeUsedMinutes: Int
)