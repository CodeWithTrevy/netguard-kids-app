// File: DailyUsageActivity.kt
package com.iconbiztechnologies1.childrenapp

import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

// Data classes remain the same
data class AppUsageItem(
    val packageName: String,
    val appName: String,
    val usageTimeMs: Long,
    val appIcon: Drawable?,
    val usagePercentage: Float
)

data class ScreenTimeSettings(
    val screenTimeTotalMinutes: Int
)

class DailyUsageActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "DailyUsageActivity"
        // This key is still used by the PARENT'S app to view a specific child device.
        const val EXTRA_DEVICE_ID = "com.iconbiztechnologies1.childrenapp.DEVICE_ID"
    }

    private lateinit var db: FirebaseFirestore
    private lateinit var totalTimeText: TextView
    private lateinit var totalTimeSubtext: TextView
    private lateinit var circularProgress: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var emptyStateView: View

    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var appUsageAdapter: AppUsageAdapter

    // Store the current device identifiers for refresh
    private var currentDocId: String? = null
    private var currentPhysicalId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_daily_usage)

        db = FirebaseFirestore.getInstance()
        initViews()
        setupRecyclerView()
        setupSwipeRefresh()

        // Initialize the data loading
        initializeDataLoading()
    }

    private fun initializeDataLoading() {
        // --- THE CORE SOLUTION: Handle two ways of starting this activity ---
        val deviceDocId = intent.getStringExtra(EXTRA_DEVICE_ID)

        if (deviceDocId != null) {
            // CASE 1: Started from Parent's device with a specific Document ID.
            Log.d(TAG, "Started with specific Document ID: $deviceDocId")
            currentDocId = deviceDocId
            currentPhysicalId = null
            loadDataForDevice(docId = deviceDocId, physicalId = null)
        } else {
            // CASE 2: Started from Child's device (e.g., after setup). Get ID from local storage.
            Log.d(TAG, "No Document ID passed. Attempting to get local Physical Device ID.")
            val physicalDeviceId = DeviceIdentityManager.getDeviceID(this)
            if (physicalDeviceId != null) {
                currentDocId = null
                currentPhysicalId = physicalDeviceId
                loadDataForDevice(docId = null, physicalId = physicalDeviceId)
            } else {
                // This is a fatal error state - the app is not set up correctly.
                Log.e(TAG, "FATAL ERROR: No Document ID passed and no local Physical ID found.")
                showEmptyState("Device not configured.")
                // Do not call finish() here if you want the user to see the empty state.
            }
        }
    }

    private fun initViews() {
        totalTimeText = findViewById(R.id.totalTimeText)
        totalTimeSubtext = findViewById(R.id.totalTimeSubtext)
        circularProgress = findViewById(R.id.circularProgress)
        recyclerView = findViewById(R.id.appUsageRecyclerView)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        emptyStateView = findViewById(R.id.emptyStateView)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Loading Usage..."
    }

    private fun setupRecyclerView() {
        appUsageAdapter = AppUsageAdapter(mutableListOf())
        recyclerView.apply {
            layoutManager = LinearLayoutManager(this@DailyUsageActivity)
            adapter = appUsageAdapter
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener {
            // ✅ Fixed: Refresh the data without recreating the activity
            refreshData()
        }
    }

    private fun refreshData() {
        // Use the stored identifiers to reload data
        loadDataForDevice(docId = currentDocId, physicalId = currentPhysicalId)
    }

    private fun loadDataForDevice(docId: String? = null, physicalId: String? = null) {
        if (docId == null && physicalId == null) {
            showEmptyState("No device specified.")
            return
        }

        swipeRefresh.isRefreshing = true
        activityScope.launch {
            try {
                // Step 1: Get the device details (name and physical ID)
                val deviceDetails = withContext(Dispatchers.IO) {
                    if (docId != null) {
                        fetchDeviceDetailsByDocId(docId)
                    } else {
                        fetchDeviceDetailsByPhysicalId(physicalId!!)
                    }
                }

                if (deviceDetails == null) {
                    showEmptyState("Could not load device.")
                    return@launch
                }

                val (deviceName, retrievedPhysicalId) = deviceDetails
                supportActionBar?.title = deviceName

                // Step 2 & 3: Use the retrieved physical ID to get usage and settings
                val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())

                val appUsageData = withContext(Dispatchers.IO) {
                    fetchTodaysAppUsage(retrievedPhysicalId, today)
                }

                val screenTimeSettings = withContext(Dispatchers.IO) {
                    fetchScreenTimeSettings(retrievedPhysicalId)
                }

                // Step 4: Update the UI with all the data
                updateUI(appUsageData, screenTimeSettings)

            } catch (e: CancellationException) {
                Log.d(TAG, "Coroutine cancelled.")
            } catch (e: Exception) {
                Log.e(TAG, "An error occurred while loading device data", e)
                showEmptyState("Error loading usage data.")
            } finally {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    // Fetches device details using the Firestore Document ID (for Parent's App)
    private suspend fun fetchDeviceDetailsByDocId(docId: String): Pair<String, String>? = suspendCancellableCoroutine { continuation ->
        db.collection("Devices").document(docId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val name = document.getString("device_name") ?: "Unknown Device"
                    val physicalId = document.getString("physical_device_id")
                    if (physicalId != null) {
                        continuation.resume(Pair(name, physicalId), null)
                    } else {
                        continuation.resume(null, null)
                    }
                } else {
                    continuation.resume(null, null)
                }
            }
            .addOnFailureListener { continuation.resume(null, null) }
    }

    // Fetches device details using the Physical Device ID (for Child's App)
    private suspend fun fetchDeviceDetailsByPhysicalId(physicalId: String): Pair<String, String>? = suspendCancellableCoroutine { continuation ->
        db.collection("Devices").whereEqualTo("physical_device_id", physicalId).limit(1).get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    val doc = documents.documents[0]
                    val name = doc.getString("device_name") ?: "This Device"
                    continuation.resume(Pair(name, physicalId), null)
                } else {
                    continuation.resume(null, null)
                }
            }
            .addOnFailureListener { continuation.resume(null, null) }
    }

    // --- NO CHANGES NEEDED FOR THE FUNCTIONS BELOW THIS LINE ---

    private suspend fun fetchTodaysAppUsage(physicalDeviceId: String, date: String): Map<String, Long> = suspendCancellableCoroutine { continuation ->
        db.collection("AppUsage")
            .whereEqualTo("physical_device_id", physicalDeviceId)
            .whereEqualTo("date", date)
            .get()
            .addOnSuccessListener { documents ->
                val appUsageMap = mutableMapOf<String, Long>()
                for (document in documents) {
                    val packageName = document.getString("package_name") ?: continue
                    val usageTimeMs = document.getLong("usage_time_ms") ?: 0L
                    appUsageMap[packageName] = appUsageMap.getOrDefault(packageName, 0L) + usageTimeMs
                }
                continuation.resume(appUsageMap, null)
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Error fetching app usage data for device: $physicalDeviceId", exception)
                continuation.resume(emptyMap(), null)
            }
    }

    private suspend fun fetchScreenTimeSettings(physicalDeviceId: String): ScreenTimeSettings? = suspendCancellableCoroutine { continuation ->
        db.collection("ScreenTime")
            .whereEqualTo("physical_device_id", physicalDeviceId)
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    val doc = documents.documents[0]
                    val settings = ScreenTimeSettings(
                        screenTimeTotalMinutes = doc.getLong("screen_time_total_minutes")?.toInt() ?: 0
                    )
                    continuation.resume(settings, null)
                } else {
                    continuation.resume(null, null)
                }
            }
            .addOnFailureListener { continuation.resume(null, null) }
    }

    private fun updateUI(appUsageData: Map<String, Long>, screenTimeSettings: ScreenTimeSettings?) {
        if (appUsageData.isEmpty()) {
            showEmptyState("No screen time recorded today.")
            return
        }

        val totalTimeMs = appUsageData.values.sum()
        val totalHours = TimeUnit.MILLISECONDS.toHours(totalTimeMs)
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs) % 60

        totalTimeText.text = if (totalHours > 0) "${totalHours}h ${totalMinutes}m" else "${totalMinutes}m"

        val limitTotalMinutes = screenTimeSettings?.screenTimeTotalMinutes ?: 0
        if (limitTotalMinutes > 0) {
            totalTimeSubtext.text = "Today's total (Limit: ${limitTotalMinutes / 60}h ${limitTotalMinutes % 60}m)"
            val usedTotalMinutes = TimeUnit.MILLISECONDS.toMinutes(totalTimeMs).toInt()
            val progress = ((usedTotalMinutes.toFloat() / limitTotalMinutes) * 100).coerceAtMost(100f)
            circularProgress.progress = progress.toInt()

            val progressColor = if (usedTotalMinutes >= limitTotalMinutes) {
                android.R.color.holo_red_dark
            } else {
                R.color.colorPrimary
            }
            circularProgress.progressDrawable.setColorFilter(
                ContextCompat.getColor(this, progressColor),
                android.graphics.PorterDuff.Mode.SRC_IN
            )
        } else {
            totalTimeSubtext.text = "Today's total (No limit set)"
            circularProgress.progress = 0
        }

        activityScope.launch {
            val items = withContext(Dispatchers.IO) {
                appUsageData.entries.sortedByDescending { it.value }.map { (pkg, time) ->
                    createAppUsageItem(pkg, time, totalTimeMs)
                }
            }
            appUsageAdapter.updateData(items)
            recyclerView.visibility = View.VISIBLE
            emptyStateView.visibility = View.GONE
        }
    }

    private fun createAppUsageItem(packageName: String, usageTimeMs: Long, totalTimeMs: Long): AppUsageItem {
        var appName = packageName
        var appIcon: Drawable? = null
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            appName = packageManager.getApplicationLabel(appInfo).toString()
            appIcon = packageManager.getApplicationIcon(appInfo)
        } catch (e: PackageManager.NameNotFoundException) {
            // App is not installed on the parent's device, use package name
        }
        val percentage = if (totalTimeMs > 0) (usageTimeMs.toFloat() / totalTimeMs) * 100f else 0f
        return AppUsageItem(packageName, appName, usageTimeMs, appIcon, percentage)
    }

    private fun showEmptyState(message: String) {
        recyclerView.visibility = View.GONE
        emptyStateView.visibility = View.VISIBLE
        totalTimeText.text = "0m"
        totalTimeSubtext.text = message
        circularProgress.progress = 0
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }
}