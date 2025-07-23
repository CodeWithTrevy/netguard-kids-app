package com.iconbiztechnologies1.childrenapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A unified accessibility service that handles app blocking based on administrative rules,
 * screen time limits, and content filtering for specific apps like browsers and YouTube.
 */
class UnifiedAppBlockingAccessibilityService : AccessibilityService() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private var currentUserId: String? = null
    private var physicalDeviceId: String? = null

    // --- Listeners ---
    private var blockedAppsListener: ListenerRegistration? = null
    private var screenTimeListener: ListenerRegistration? = null

    // --- Coroutines & Timers ---
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var resetCheckJob: Job? = null

    // --- State Management ---
    private val blockedApps = mutableSetOf<String>()
    private var isTimeBasedBlockingEnabled = false
    private var dailyScreenTimeLimitMinutes = 0L
    private var currentDayUsageMinutes = 0L

    // --- Service Internals ---
    private var currentForegroundApp: String? = null
    private var lastBlockedPackage = ""
    private var lastBlockTime = 0L
    private val isBlockingActivityActive = AtomicBoolean(false)
    private var isReceiverRegistered = false

    // --- Content Filtering State ---
    private var lastCheckedBrowserUrl = ""
    private var browserUrlCheckTime = 0L
    private var lastYouTubeCheckTime = 0L

    companion object {
        private const val TAG = "UnifiedAppBlockingService"

        // --- Timings ---
        private const val CHECK_INTERVAL_MS = 60000L // 1 minute for reset time check
        private const val BLOCK_COOLDOWN_MS = 2000L // Prevent rapid re-blocking
        private const val URL_CHECK_COOLDOWN_MS = 1000L
        private const val YOUTUBE_CHECK_COOLDOWN_MS = 1500L

        // --- Block Reasons ---
        const val REASON_ADMIN = "APP_BLOCKED_ADMIN"
        const val REASON_TIME = "APP_BLOCKED_TIME"
        const val REASON_URL = "URL_BLOCKED"
        const val REASON_YOUTUBE = "YOUTUBE_CONTENT_BLOCKED"

        // --- Firestore Collections ---
        private const val BLOCKED_APPS_COLLECTION = "blocked_apps"
        private const val SCREEN_TIME_COLLECTION = "ScreenTime"

        // --- App Categories ---
        private val EMERGENCY_APPS = setOf(
            "com.android.dialer", "com.android.phone", "com.samsung.android.dialer",
            "com.google.android.dialer", "com.android.contacts", "com.android.emergency",
            "com.iconbiztechnologies1.childrenapp"
        )
        private val SYSTEM_APPS = setOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.launcher",
            "com.android.launcher3", "com.google.android.apps.nexuslauncher",
            "com.transsion.hilauncher", "com.sec.android.app.launcher",
            "com.iconbiztechnologies1.childrenapp"
        )
        private val CONTENT_FILTER_APPS = setOf(
            "com.android.chrome", "org.mozilla.firefox", "com.microsoft.emmx",
            "com.opera.browser", "com.brave.browser", "com.duckduckgo.mobile.android",
            "com.google.android.youtube"
        )

        // --- Content Filtering Rules ---
        private val BLOCKED_DOMAINS = setOf(
            "pornhub.com", "xnxx.com", "xvideos.com", "redtube.com", "youporn.com",
            "tube8.com", "spankbang.com", "eporner.com", "hqporner.com", "4tube.com"
        )
        private val BLOCKED_KEYWORDS = setOf(
            "porn", "xxx", "sex", "adult", "nsfw", "naked", "nude", "erotic",
            "hardcore", "masturbation", "fetish", "bdsm", "webcam", "cam girl"
        )

        // --- Permission Request State ---
        @Volatile
        private var isRequestingPermission = false
    }

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED") {
                isBlockingActivityActive.set(false)
                Log.d(TAG, "Blocking activity finished, ready for next block.")
            }
        }
    }

    // --- Service Lifecycle ---

    override fun onCreate() {
        super.onCreate()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        Log.d(TAG, "Service creating.")

        currentUserId = auth.currentUser?.uid
        physicalDeviceId = DeviceIdentityManager.getDeviceID(this)

        if (currentUserId != null && physicalDeviceId != null) {
            setupFirebaseListeners()
        } else {
            Log.e(TAG, "FATAL: Cannot setup listeners. UserID or DeviceID is null.")
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this, "Netguard kids app cannot run without user login.", Toast.LENGTH_LONG).show()
            }
        }

        registerServiceReceiver()
        startResetTimeChecker()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
            packageNames = null // Monitor all apps for maximum coverage
        }
        Log.d(TAG, "Accessibility service connected. Verifying permissions.")
        // This is a more reliable place to check for permissions.
        checkAndRequestPermissions()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // First, ensure permissions are still active. The service can be running but in a bad state.
        if (!isDeviceAdminActive()) {
            checkAndRequestPermissions()
            return // Skip event processing until permissions are re-granted.
        }
        if (event == null || isBlockingActivityActive.get()) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> event.source?.let { handleWindowContentChanged(it) }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service was interrupted.")
        // The service might be temporarily disabled by the system.
        // It's a good place to log this event, but avoid re-requesting permissions aggressively here.
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.w(TAG, "Service is being destroyed.")

        serviceScope.cancel() // Cancel all coroutines
        blockedAppsListener?.remove()
        screenTimeListener?.remove()
        resetCheckJob?.cancel()

        if (isReceiverRegistered) {
            try {
                unregisterReceiver(broadcastReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered or already unregistered.", e)
            }
        }
        isReceiverRegistered = false
    }

    // --- Setup & Configuration ---

    private fun setupFirebaseListeners() {
        setupFirebaseBlockedAppsListener()
        setupScreenTimeListener()
    }

    private fun setupFirebaseBlockedAppsListener() {
        val userId = currentUserId ?: return
        val deviceId = physicalDeviceId ?: return
        Log.d(TAG, "Setting up Firebase listener for blocked apps on device: $deviceId")

        blockedAppsListener?.remove()
        val docRef = firestore.collection(BLOCKED_APPS_COLLECTION)
            .document(userId).collection("devices").document(deviceId).collection("apps")

        blockedAppsListener = docRef.addSnapshotListener { snapshots, error ->
            if (error != null) {
                Log.e(TAG, "Blocked apps listener error", error)
                return@addSnapshotListener
            }
            val newBlockedSet = snapshots?.documents?.map { it.id }?.toSet() ?: emptySet()
            if (blockedApps != newBlockedSet) {
                Log.d(TAG, "Blocked apps list updated. New list: $newBlockedSet")
                blockedApps.clear()
                blockedApps.addAll(newBlockedSet)
                // Immediately check if the current app needs to be blocked
                currentForegroundApp?.let { if (getBlockReason(it) != null) blockApp(it, REASON_ADMIN) }
            }
        }
    }

    private fun setupScreenTimeListener() {
        val userId = currentUserId ?: return
        val deviceId = physicalDeviceId ?: return
        Log.d(TAG, "Setting up screen time listener for device: $deviceId")

        screenTimeListener?.remove()
        val docRef = firestore.collection(SCREEN_TIME_COLLECTION)
            .document(userId).collection("devices").document(deviceId)

        screenTimeListener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Screen time listener error", error)
                return@addSnapshotListener
            }

            if (snapshot != null && snapshot.exists()) {
                dailyScreenTimeLimitMinutes = snapshot.getLong("screen_time_total_minutes") ?: 0L
                currentDayUsageMinutes = snapshot.getLong("current_usage_minutes") ?: 0L
                Log.d(TAG, "Screen time updated: Limit=${dailyScreenTimeLimitMinutes}m, Usage=${currentDayUsageMinutes}m")
                updateBlockingStateBasedOnUsage()
            } else {
                Log.w(TAG, "No screen time document found. Disabling time-based blocking.")
                disableTimeBasedBlocking()
            }
        }
    }

    private fun registerServiceReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(broadcastReceiver, filter)
            }
            isReceiverRegistered = true
            Log.d(TAG, "Service broadcast receiver registered.")
        }
    }

    // --- Event Handling ---

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName != currentForegroundApp) {
            currentForegroundApp = packageName
            Log.d(TAG, "Foreground app changed to: $packageName")
            getBlockReason(packageName)?.let { reason ->
                blockApp(packageName, reason)
            }
        }
    }

    private fun handleWindowContentChanged(source: AccessibilityNodeInfo) {
        val packageName = source.packageName?.toString() ?: return
        if (!CONTENT_FILTER_APPS.contains(packageName)) return

        if (packageName.contains("youtube")) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastYouTubeCheckTime > YOUTUBE_CHECK_COOLDOWN_MS) {
                lastYouTubeCheckTime = currentTime
                checkForProhibitedTextInYouTube(source)
            }
        } else { // Browser apps
            val currentTime = System.currentTimeMillis()
            if (currentTime - browserUrlCheckTime > URL_CHECK_COOLDOWN_MS) {
                browserUrlCheckTime = currentTime
                checkForBlockedBrowserContent(source)
            }
        }
    }

    // --- Blocking Logic ---

    /**
     * Centralized logic to determine if an app should be blocked and why.
     * @return A string constant representing the block reason, or null if no block is needed.
     */
    private fun getBlockReason(packageName: String): String? {
        if (EMERGENCY_APPS.contains(packageName) || SYSTEM_APPS.contains(packageName)) {
            return null
        }
        if (blockedApps.contains(packageName)) {
            return REASON_ADMIN
        }
        if (isTimeBasedBlockingEnabled) {
            return REASON_TIME
        }
        return null
    }

    private fun updateBlockingStateBasedOnUsage() {
        val screenTimeExceeded = dailyScreenTimeLimitMinutes > 0 && currentDayUsageMinutes >= dailyScreenTimeLimitMinutes
        if (screenTimeExceeded != isTimeBasedBlockingEnabled) {
            isTimeBasedBlockingEnabled = screenTimeExceeded
            Log.i(TAG, "Time-based blocking is now ${if (isTimeBasedBlockingEnabled) "ACTIVE" else "INACTIVE"}")
            // If blocking just became active, check if the current app needs to be blocked.
            if (isTimeBasedBlockingEnabled) {
                currentForegroundApp?.let { if (getBlockReason(it) == REASON_TIME) blockApp(it, REASON_TIME) }
            }
        }
    }

    private fun disableTimeBasedBlocking() {
        if (isTimeBasedBlockingEnabled) {
            isTimeBasedBlockingEnabled = false
            Log.d(TAG, "Time-based blocking is now INACTIVE.")
        }
    }

    private fun blockApp(packageName: String, blockType: String, contentDescriptor: String? = null) {
        val currentTime = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && currentTime - lastBlockTime < BLOCK_COOLDOWN_MS) {
            return // In cooldown period
        }

        if (isBlockingActivityActive.compareAndSet(false, true)) {
            lastBlockedPackage = packageName
            lastBlockTime = currentTime
            val appName = getAppName(packageName)
            Log.e(TAG, "BLOCKING: $appName ($packageName) | Type: $blockType | Content: '${contentDescriptor ?: "N/A"}'")

            goToHomeScreen()
            // Launch blocking activity after a short delay to ensure the home screen is visible
            Handler(Looper.getMainLooper()).postDelayed({
                launchBlockingActivity(packageName, appName, blockType, contentDescriptor)
            }, 250)
        }
    }

    /**
     * UPDATED: This function now routes to the correct blocking Activity based on the block type.
     */
    private fun launchBlockingActivity(pkg: String, appName: String, type: String, content: String?) {
        val intent: Intent

        if (type == REASON_TIME) {
            // For time-based blocking, use the dedicated screen time activity.
            Log.d(TAG, "Launching screen time blocking activity: AppBlockedActivity")
            intent = Intent(this, AppBlockedActivity::class.java)
        } else {
            // For Admin, URL, or YouTube blocks, use the stronger Device Admin blocking activity.
            Log.d(TAG, "Launching Device Admin blocking activity for type '$type': AppBlockedActivity1")
            intent = Intent(this, AppBlockedActivity1::class.java).apply {
                putExtra("blocked_package", pkg)
                putExtra("blocked_app_name", appName)
                putExtra("block_type", type) // Pass the type for more specific messaging
                content?.let { putExtra("content_descriptor", it) }
            }
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        startActivity(intent)
    }


    // --- Content Filtering ---

    private fun checkForBlockedBrowserContent(source: AccessibilityNodeInfo) {
        extractUrlFromAccessibilityNode(source)?.let { url ->
            if (url != lastCheckedBrowserUrl) {
                lastCheckedBrowserUrl = url
                Log.d(TAG, "Browser URL detected: $url")
                if (isUrlBlockedByDomainOrKeyword(url)) {
                    Log.w(TAG, "URL is blocked: $url")
                    blockApp(source.packageName.toString(), REASON_URL, url)
                }
            }
        }
    }

    private fun extractUrlFromAccessibilityNode(node: AccessibilityNodeInfo): String? {
        val urlBarIds = listOf(
            "com.android.chrome:id/url_bar", "org.mozilla.firefox:id/url_bar_title",
            "com.microsoft.emmx:id/url_bar", "com.opera.browser:id/url_field",
            "com.brave.browser:id/url_bar", "com.duckduckgo.mobile.android:id/omnibarTextInput"
        )
        for (id in urlBarIds) {
            node.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.text?.toString()?.let {
                if (it.isNotEmpty()) return it
            }
        }
        return null
    }

    private fun isUrlBlockedByDomainOrKeyword(url: String): Boolean {
        val lowerCaseUrl = url.lowercase()
        return BLOCKED_DOMAINS.any { lowerCaseUrl.contains(it) } || BLOCKED_KEYWORDS.any { lowerCaseUrl.contains(it) }
    }

    private fun checkForProhibitedTextInYouTube(source: AccessibilityNodeInfo) {
        findTextInNode(source) { text ->
            val lowerCaseText = text.lowercase()
            val foundKeyword = BLOCKED_KEYWORDS.find { lowerCaseText.contains(it) }
            if (foundKeyword != null) {
                Log.w(TAG, "Blocked keyword '$foundKeyword' found in YouTube content: '$text'")
                blockApp("com.google.android.youtube", REASON_YOUTUBE, text)
                return@findTextInNode true // Stop searching
            }
            false // Continue searching
        }
    }

    /**
     * Performs an efficient, non-recursive (iterative) search for text within a node tree.
     * @param node The root node to start the search from.
     * @param onFound A predicate that returns true to stop the search, or false to continue.
     */
    private fun findTextInNode(node: AccessibilityNodeInfo?, onFound: (String) -> Boolean) {
        if (node == null) return
        val queue: Queue<AccessibilityNodeInfo> = LinkedList()
        queue.add(node)

        while (queue.isNotEmpty()) {
            val currentNode = queue.poll() ?: continue

            val text = currentNode.text?.toString()
            val contentDesc = currentNode.contentDescription?.toString()

            if (!text.isNullOrEmpty() && onFound(text)) return
            if (!contentDesc.isNullOrEmpty() && onFound(contentDesc)) return

            for (i in 0 until currentNode.childCount) {
                currentNode.getChild(i)?.let { queue.add(it) }
            }
            // Avoid memory leaks
            currentNode.recycle()
        }
    }

    // --- Daily Reset Logic ---

    private fun startResetTimeChecker() {
        resetCheckJob?.cancel()
        resetCheckJob = serviceScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkResetTime()
            }
        }
    }

    private fun checkResetTime() {
        try {
            // The reset time is always midnight ("00:00").
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            if (sdf.format(Date()) == "00:00" && currentDayUsageMinutes > 0) {
                Log.i(TAG, "Midnight reset time reached. Resetting daily usage.")
                resetDailyUsage()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkResetTime", e)
        }
    }

    private fun resetDailyUsage() {
        currentDayUsageMinutes = 0
        Log.d(TAG, "Local daily usage reset to 0.")
        updateBlockingStateBasedOnUsage()
        // Here you would also update Firestore to reset the usage for the day.
        // This is a critical step for persistence.
        val userId = currentUserId ?: return
        val deviceId = physicalDeviceId ?: return
        firestore.collection(SCREEN_TIME_COLLECTION)
            .document(userId).collection("devices").document(deviceId)
            .update("current_usage_minutes", 0)
            .addOnSuccessListener { Log.i(TAG, "Firestore daily usage successfully reset to 0.") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to reset Firestore daily usage.", e) }
    }

    // --- Helper & Utility Functions ---

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "App name not found for package: $packageName")
            packageName
        }
    }

    private fun goToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
    }

    // --- Permissions ---

    /**
     * Checks if the accessibility service is enabled. This is a more robust check.
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val settingValue = Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        if (settingValue != null) {
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(settingValue)
            while (splitter.hasNext()) {
                val componentName = splitter.next()
                if (componentName.equals(
                        "${packageName}/${UnifiedAppBlockingAccessibilityService::class.java.name}",
                        ignoreCase = true
                    )
                ) {
                    return true
                }
            }
        }
        return false
    }

    private fun isDeviceAdminActive(): Boolean {
        return dpm.isAdminActive(adminComponent)
    }

    /**
     * Checks for necessary permissions and requests them if missing.
     * Uses a flag to prevent multiple requests from being launched simultaneously.
     */
    private fun checkAndRequestPermissions() {
        // Prevent re-entrant calls
        if (isRequestingPermission) {
            return
        }

        if (!isAccessibilityServiceEnabled()) {
            requestAccessibilityPermission()
            return // Request one at a time
        }
        if (!isDeviceAdminActive()) {
            requestDeviceAdminPermission()
        }
    }

    private fun requestAccessibilityPermission() {
        isRequestingPermission = true
        Log.w(TAG, "Accessibility service is not enabled. Requesting...")
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "Please enable the 'Netguard kids' service.", Toast.LENGTH_LONG).show()
        }
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        startActivity(intent)

        // Reset the flag after a delay to allow the user to interact with the settings screen
        Handler(Looper.getMainLooper()).postDelayed({
            isRequestingPermission = false
        }, 5000) // 5-second cooldown
    }

    private fun requestDeviceAdminPermission() {
        isRequestingPermission = true
        Log.w(TAG, "Device admin is not active. Requesting...")
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "This permission is required to enforce parental rules and block applications.")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        startActivity(intent)

        Handler(Looper.getMainLooper()).postDelayed({
            isRequestingPermission = false
        }, 5000) // 5-second cooldown
    }
}