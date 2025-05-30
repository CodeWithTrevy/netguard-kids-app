package com.iconbiztechnologies1.childrenapp

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import java.lang.ref.WeakReference
import android.os.Handler
import android.os.Looper
import android.net.Uri

class UnifiedAppBlockingAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "UnifiedAppBlockingService"
        private const val CHECK_INTERVAL = 10000L // Check every 10 seconds for time reset
        private const val BLOCK_COOLDOWN = 2000L // 2 second cooldown between any app blocks
        private const val URL_CHECK_COOLDOWN = 1000L // 1 second cooldown for browser URL checks
        private const val YOUTUBE_CHECK_COOLDOWN = 1500L // 1.5 second cooldown for YouTube specific checks

        private const val BLOCKED_APPS_COLLECTION = "blocked_apps"
        private const val SCREEN_TIME_COLLECTION = "ScreenTime"

        // Emergency apps that should never be blocked
        private val EMERGENCY_APPS = setOf(
            "com.android.dialer", "com.android.phone", "com.samsung.android.dialer",
            "com.google.android.dialer", "com.android.contacts", "com.sh.smart.caller",
            "com.android.emergency", "com.iconbiztechnologies1.childrenapp", "android"
        )

        // System apps that should not be blocked (includes our own app)
        private val SYSTEM_APPS = setOf(
            "android", "com.android.systemui", "com.android.launcher", "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", "com.transsion.hilauncher",
            "com.iconbiztechnologies1.childrenapp"
        )

        // Apps considered for content filtering (browsers and YouTube)
        private val CONTENT_FILTER_APPS = setOf(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "com.microsoft.emmx",
            "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native",
            "com.UCMobile.intl", "com.brave.browser", "com.duckduckgo.mobile.android",
            "com.kiwibrowser.browser", "com.sec.android.app.sbrowser", "mark.via.gp",
            "com.jiubang.browser", "com.android.browser", "org.chromium.chrome",
            "com.google.android.youtube", // YouTube included for content filtering
            "com.google.android.youtube.tv", "com.google.android.apps.youtube.music",
            "com.google.android.youtube.tvkids", "com.google.android.apps.searchlite",
            "com.google.android.googlequicksearchbox"
        )

        // Blocked domains for content filtering (primarily for browsers)
        private val BLOCKED_DOMAINS = setOf(
            "tblop.com", "escorthub.org", "bedescorts.com", "icanhazchat.com", "legalporno.com",
            "ugandahotgirls.com", "exoticuganda.com", "ugandahotbabes.com", "kampalahot.com",
            "xnxx.com", "xnxx.tv", "kampalahotgirls.com", "eporner.com", "pornhub.com",
            "watch-my-gf.com", "pornfaze.com", "jacquieetmicheltv.net", "thepornlist.net",
            "bienzorras.com", "qiqitv.info", "ichatonline.com", "18abused.com", "pornx.to",
            "pornmeka.com", "putarianocelular.com", "loveblowjobs.com", "bitchleaks.com",
            "theyarehuge.com", "inxxx.com", "ah-me.com", "pornhex.com", "pornhd8k.co",
            "beautymovies.com", "bravoporno.net", "sambapornogratis.com.br", "mature.red",
            "moms.red", "mothers.red", "titanicporn.com", "sis-love-me.com", "fapcat.com",
            "pimpbunny.com", "18nabused.com", "lavideodujourjetm.net", "leakedzone.pro",
            "teenleaks.net", "sunnyleonesexvideo.net", "fuqqt.com", "xxxmaturevideos.com",
            "feet9.com", "yespornpleasexxx.com", "ceskeporno.cz", "mypornhere.com",
            "amateurporn.me", "home-made-videos.com", "private-sex-tapes.com",
            "thepornosites.com", "amateur-couples.com", "sexvid.me", "ladraodepacks.com"
        )

        // Additional blocked keywords for URL and YouTube content filtering
        private val BLOCKED_KEYWORDS = setOf(
            "porn", "xxx", "sex", "adult", "mature", "nude", "naked", "gonewild", "hot sex",
            "nsfw", "18+", "erotic", "explicit", "hardcore", "xvideos", "casino", "gambling",
            "poker", "bet", "lottery", "slots", "onlyfuns", "pornfaze", "xnxx", "best porn videos",
            "pornhub", "ichatonline", "pornx", "pornmeka", "vintage", "gay", "lesbian",
            "interracial", "trans", "amateur couples", "big breasts", "masturbation",
            "foot worship", "horror", "onlyfans", "ugandahotgirls.com"
            // Add more keywords as needed, be mindful of common words that could be false positives
        )

        // Example YouTube Search Bar IDs - **VERIFY AND UPDATE THESE WITH LAYOUT INSPECTOR**
        private val YOUTUBE_SEARCH_BAR_IDS = listOf(
            "com.google.android.youtube:id/search_edit_text",
            "com.google.android.youtube:id/query_text_layout", // This might be a layout containing the text
            "com.google.android.youtube:id/search_query"
            // Add more potential IDs here
        )

        @Volatile
        private var serviceInstance: WeakReference<UnifiedAppBlockingAccessibilityService>? = null

        fun enableBlocking(context: Context, resetTime: String) {
            Log.d(TAG, "🔐 Attempting to enable blocking with reset time: $resetTime")
            serviceInstance?.get()?.enableTimeBasedBlocking(resetTime) ?: run {
                Log.d(TAG, "📡 Service instance not available for enableBlocking, using broadcast")
                val intent = Intent("com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING").apply {
                    putExtra("reset_time", resetTime)
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            }
        }

        fun disableBlocking(context: Context) {
            Log.d(TAG, "🔓 Attempting to disable blocking")
            serviceInstance?.get()?.disableTimeBasedBlocking() ?: run {
                Log.d(TAG, "📡 Service instance not available for disableBlocking, using broadcast")
                val intent = Intent("com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING")
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
            }
        }

        fun isServiceRunning(): Boolean {
            val running = serviceInstance?.get() != null
            Log.d(TAG, "Service running status: $running")
            return running
        }
    }

    // Firebase
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private var blockedAppsListener: ListenerRegistration? = null
    private var screenTimeListener: ListenerRegistration? = null
    private val blockedApps = mutableSetOf<String>() // Apps blocked by parent directly

    // Time-based blocking
    private var isTimeBasedBlockingEnabled = false
    private var resetTime = "" // HH:mm format
    private var resetCheckJob: Job? = null

    // General Service State
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentForegroundApp: String? = null
    private var lastBlockedPackage = ""
    private var lastBlockTime = 0L // Cooldown for any block action
    private var isBlockingActivityActive = false // To prevent re-blocking our own block screen

    // URL/Content Filtering State
    private var lastCheckedBrowserUrl = "" // For traditional browsers
    private var browserUrlCheckTime = 0L
    private var lastYouTubeCheckTime = 0L // For YouTube specific checks

    private var isReceiverRegistered = false
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                "com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING" -> {
                    val resetTime = intent.getStringExtra("reset_time") ?: ""
                    Log.d(TAG, "📡 Received ENABLE_BLOCKING broadcast: $resetTime")
                    enableTimeBasedBlocking(resetTime)
                }
                "com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING" -> {
                    Log.d(TAG, "📡 Received DISABLE_BLOCKING broadcast")
                    disableTimeBasedBlocking()
                }
                "com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED" -> {
                    Log.d(TAG, "📡 Received BLOCKING_ACTIVITY_FINISHED broadcast")
                    isBlockingActivityActive = false
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceInstance = WeakReference(this)
        Log.d(TAG, "UnifiedAppBlockingAccessibilityService created")
        setupFirebaseBlockedAppsListener()
        setupScreenTimeListener()
        registerServiceReceiver() // Renamed for clarity
        startResetTimeChecker()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED // Key events
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or // May help in identifying views
                    AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY // For webviews
            notificationTimeout = 100 // Standard timeout
            packageNames = null // Monitor all apps, will filter internally
        }
        serviceInfo = info
        Log.d(TAG, "Unified accessibility service connected and configured.")
        // Re-setup listeners if they somehow got disconnected, though onCreate should handle it
        if (blockedAppsListener == null) setupFirebaseBlockedAppsListener()
        if (screenTimeListener == null) setupScreenTimeListener()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || isBlockingActivityActive) return // Skip if our block screen is up

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowStateChanged(event)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // Only process if source is not null
                if (event.source != null) {
                    handleWindowContentChanged(event)
                } else {
                    // Log.v(TAG, "WindowContentChanged event with null source for ${event.packageName}") // Verbose
                }
            }
        }
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName != currentForegroundApp) {
            currentForegroundApp = packageName
            Log.d(TAG, "App in foreground: $packageName")

            if (shouldBlockCurrentApp(packageName)) { // General app blocking (time/parental)
                blockApp(packageName, "APP_BLOCKED")
            }
        }
    }

    private fun handleWindowContentChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (CONTENT_FILTER_APPS.contains(packageName)) {
            if (packageName == "com.google.android.youtube") {
                checkForProhibitedTextInYouTube(event)
            } else {
                // For other browsers in CONTENT_FILTER_APPS
                checkForBlockedBrowserContent(event)
            }
        }
    }

    // --- Browser URL Filtering ---
    private fun checkForBlockedBrowserContent(event: AccessibilityEvent) {
        val source = event.source ?: return // event.source should be non-null due to check in onAccessibilityEvent
        val currentTime = System.currentTimeMillis()

        if (currentTime - browserUrlCheckTime < URL_CHECK_COOLDOWN) return
        browserUrlCheckTime = currentTime

        val url = extractUrlFromAccessibilityNode(source) // Optimized for browsers
        if (url != null && url != lastCheckedBrowserUrl) {
            lastCheckedBrowserUrl = url
            Log.d(TAG, "Browser URL Checking: $url in ${event.packageName}")
            if (isUrlBlockedByDomainOrKeyword(url)) {
                Log.d(TAG, "🚫 BLOCKING Browser URL: $url")
                blockApp(event.packageName.toString(), "URL_BLOCKED", url)
            }
        }
    }

    private fun extractUrlFromAccessibilityNode(node: AccessibilityNodeInfo): String? {
        // Try common address bar IDs first
        val addressBarIds = listOf(
            "com.android.chrome:id/url_bar", "org.mozilla.firefox:id/url_bar_title",
            "com.microsoft.emmx:id/url_bar", "com.opera.browser:id/url_field",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text"
            // Add more known browser address bar IDs
        )
        for (id in addressBarIds) {
            val addressNodes = node.findAccessibilityNodeInfosByViewId(id)
            if (addressNodes != null && addressNodes.isNotEmpty()) {
                val text = addressNodes[0]?.text?.toString()
                addressNodes.forEach { it.recycle() }
                if (text != null && (text.startsWith("http://") || text.startsWith("https://"))) {
                    return text
                }
            }
        }
        // Fallback: search for EditText containing http, often the URL bar if ID is unknown
        return findUrlInEditTextRecursively(node)
    }

    private fun findUrlInEditTextRecursively(node: AccessibilityNodeInfo?): String? {
        if (node == null) return null
        if ("android.widget.EditText" == node.className) {
            val text = node.text?.toString()
            if (text != null && (text.startsWith("http://") || text.startsWith("https://") || text.contains("."))) {
                // Basic validation for URL-like string
                if (text.matches(Regex("^https?://.*")) || text.count { it == '.' } >=1) {
                    return text
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val foundUrl = findUrlInEditTextRecursively(child)
            child?.recycle() // Recycle child after checking
            if (foundUrl != null) return foundUrl
        }
        return null
    }


    private fun isUrlBlockedByDomainOrKeyword(url: String): Boolean {
        val lowerUrl = url.lowercase()
        try {
            val uri = Uri.parse(lowerUrl)
            val host = uri.host?.lowercase()

            if (host != null && BLOCKED_DOMAINS.any { domain -> host == domain || host.endsWith(".$domain") }) {
                Log.d(TAG, "URL blocked by domain: $host")
                return true
            }
            if (BLOCKED_KEYWORDS.any { keyword -> lowerUrl.contains(keyword.lowercase()) }) {
                Log.d(TAG, "URL blocked by keyword in URL: $lowerUrl")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing URL for blocking: $url", e)
            // Optionally block if parsing fails, as a precaution
            if (BLOCKED_KEYWORDS.any { keyword -> lowerUrl.contains(keyword.lowercase()) }) {
                Log.d(TAG, "URL (unparsable) blocked by keyword: $lowerUrl")
                return true
            }
        }
        return false
    }

    // --- YouTube Content Filtering ---
    private fun checkForProhibitedTextInYouTube(event: AccessibilityEvent) {
        val source = event.source ?: return // event.source should be non-null
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastYouTubeCheckTime < YOUTUBE_CHECK_COOLDOWN) return
        lastYouTubeCheckTime = currentTime

        // 1. Try specific elements first (e.g., search bar)
        if (checkYouTubeSpecificElements(source, event.packageName.toString())) {
            return // Blocked by specific element
        }

        // 2. Fallback to general screen text scraping if no specific block
        // This is more resource-intensive.
        checkGeneralScreenTextForKeywords(source, event.packageName.toString())
    }

    private fun checkYouTubeSpecificElements(rootNode: AccessibilityNodeInfo, packageName: String): Boolean {
        // **IMPORTANT**: YOUTUBE_SEARCH_BAR_IDS are examples. Use Layout Inspector!
        for (searchBarId in YOUTUBE_SEARCH_BAR_IDS) {
            val searchInputNodes = rootNode.findAccessibilityNodeInfosByViewId(searchBarId)
            if (searchInputNodes != null && searchInputNodes.isNotEmpty()) {
                for(node in searchInputNodes) {
                    val searchText = node.text?.toString()?.lowercase()
                    if (!searchText.isNullOrEmpty()) {
                        Log.d(TAG, "YouTube Specific Element ($searchBarId) Text: '$searchText'")
                        val matchedKeyword = BLOCKED_KEYWORDS.firstOrNull { keyword -> searchText.contains(keyword.lowercase()) }
                        if (matchedKeyword != null) {
                            Log.d(TAG, "🚫 BLOCKING YouTube (Specific: $searchBarId) for keyword: '$matchedKeyword'")
                            blockApp(packageName, "URL_BLOCKED", "Blocked search: '$matchedKeyword'")
                            node.recycle()
                            // Recycle other nodes from the list if any, though usually it's one primary search box
                            searchInputNodes.filterNot { it == node }.forEach { it.recycle() }
                            return true
                        }
                    }
                    node.recycle()
                }
            }
        }
        // Consider adding checks for video titles or descriptions here if you can reliably identify their nodes.
        // This is complex. Example: find all TextViews and check their content.
        return false
    }

    private fun checkGeneralScreenTextForKeywords(rootNode: AccessibilityNodeInfo, packageName: String) {
        val allTextOnScreen = mutableListOf<String>()
        collectTextFromNode(rootNode, allTextOnScreen)
        // Do not recycle rootNode (event.source) here, system manages it.

        if (allTextOnScreen.isEmpty()) return

        val combinedText = allTextOnScreen.joinToString(separator = " ").lowercase()
        Log.d(TAG, "YouTube General Screen Text Scrape: '$combinedText'") // Can be very verbose

        val matchedKeyword = BLOCKED_KEYWORDS.firstOrNull { keyword -> combinedText.contains(keyword.lowercase()) }
        if (matchedKeyword != null) {
            Log.d(TAG, "🚫 BLOCKING YouTube (General Text) for keyword: '$matchedKeyword'")
            blockApp(packageName, "URL_BLOCKED", "Blocked content: '$matchedKeyword'")
        }
    }

    private fun collectTextFromNode(nodeInfo: AccessibilityNodeInfo?, collectedText: MutableList<String>) {
        if (nodeInfo == null) return

        if (nodeInfo.isVisibleToUser && nodeInfo.text != null && nodeInfo.text.toString().isNotBlank()) {
            collectedText.add(nodeInfo.text.toString().trim())
        }

        for (i in 0 until nodeInfo.childCount) {
            val childNode = nodeInfo.getChild(i)
            collectTextFromNode(childNode, collectedText)
            childNode?.recycle() // Recycle children after processing
        }
    }


    // --- Core Blocking Logic ---
    private fun shouldBlockCurrentApp(packageName: String): Boolean {
        if (isBlockingActivityActive && packageName == this.packageName) return false // Don't block our own block screen
        if (EMERGENCY_APPS.contains(packageName) || SYSTEM_APPS.contains(packageName)) return false

        val shouldBlockByFirebase = blockedApps.contains(packageName)
        val shouldBlockByTime = isTimeBasedBlockingEnabled && !isSystemOrLauncherApp(packageName) // Don't time-block launchers/system

        if (shouldBlockByFirebase) Log.d(TAG, "$packageName is Firebase blocked.")
        if (shouldBlockByTime) Log.d(TAG, "$packageName is time blocked (Reset: $resetTime).")

        return shouldBlockByFirebase || shouldBlockByTime
    }

    private fun blockApp(packageName: String, blockType: String, contentDescriptor: String? = null) {
        val currentTime = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && currentTime - lastBlockTime < BLOCK_COOLDOWN) {
            Log.d(TAG, "Block cooldown for $packageName. Skipping.")
            return
        }
        // Check if the app to be blocked is our own blocking activity - this shouldn't happen if isBlockingActivityActive is managed well
        if (packageName == this.packageName && (contentDescriptor?.contains("Blocked search") == true || contentDescriptor?.contains("Blocked content") == true || contentDescriptor?.startsWith("http") == true)){
            // This might mean our UrlBlockedActivity itself triggered a block, which is a loop.
            // Only set isBlockingActivityActive if we are sure we are launching our OWN blocking UI.
            // The check for isBlockingActivityActive at the start of onAccessibilityEvent should prevent this.
            Log.w(TAG, "Attempted to block self while showing a block. This is a potential loop. Current isBlockingActivityActive: $isBlockingActivityActive")
            if (!isBlockingActivityActive) isBlockingActivityActive = true; // Set it if we are indeed launching a block screen
            // else return; // If already active, we shouldn't proceed to re-block
        } else {
            isBlockingActivityActive = true // Set true as we are about to show a block screen
        }


        lastBlockedPackage = packageName
        lastBlockTime = currentTime

        val appName = getAppName(packageName)
        Log.d(TAG, "🚫 BLOCKING: $packageName ($appName) - Type: $blockType, Descriptor: $contentDescriptor")

        goToHomeScreen() // Go home first to interrupt current app

        Handler(Looper.getMainLooper()).postDelayed({
            launchBlockingActivity(packageName, appName, blockType, contentDescriptor)
        }, 300) // Short delay for home screen to settle
    }

    private fun goToHomeScreen() {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            homeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(homeIntent)
            Log.d(TAG, "Navigated to home screen.")
        } catch (e: Exception) {
            Log.e(TAG, "Error navigating to home screen", e)
        }
    }

    private fun launchBlockingActivity(packageName: String, appName: String, blockType: String, contentDescriptor: String?) {
        try {
            val activityClass = when (blockType) {
                "URL_BLOCKED" -> UrlBlockedActivity::class.java
                "APP_BLOCKED" -> if (blockedApps.contains(packageName)) AppBlockedActivity1::class.java else AppBlockedActivity::class.java
                else -> AppBlockedActivity1::class.java // Default
            }

            val intent = Intent(this, activityClass).apply {
                putExtra("blocked_package", packageName)
                putExtra("blocked_app_name", appName)

                if (blockType == "URL_BLOCKED") {
                    putExtra(UrlBlockedActivity.EXTRA_BLOCKED_URL, contentDescriptor ?: "Content restricted")
                } else {
                    putExtra("reset_time", resetTime) // For AppBlockedActivity (time-based)
                    putExtra("blocking_reason", if (blockedApps.contains(packageName)) "PARENT_BLOCKED" else "SCREEN_TIME_LIMIT")
                }

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            Log.d(TAG, "Launched ${activityClass.simpleName} for $packageName.")
        } catch (e: Exception) {
            Log.e(TAG, "Error launching blocking activity for $packageName", e)
            isBlockingActivityActive = false // Reset if launch fails
        }
    }

    // --- Helper Methods ---
    private fun isSystemOrLauncherApp(packageName: String): Boolean {
        if (SYSTEM_APPS.contains(packageName)) return true
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfo = packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun getAppName(packageName: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName // Fallback to package name
        }
    }

    // --- Firebase Listeners & Time Blocking Logic (largely unchanged from your provided code) ---
    private fun setupFirebaseBlockedAppsListener() { /* ... Your existing code ... */
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "User not authenticated for Firebase blocking")
            return
        }
        Log.d(TAG, "Setting up Firebase blocked apps listener for user: $userId")
        blockedAppsListener?.remove()
        blockedAppsListener = firestore.collection(BLOCKED_APPS_COLLECTION)
            .document("parent_blocked_apps") // Assuming a structure
            .collection(userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to Firebase blocked apps", error)
                    return@addSnapshotListener
                }
                val newBlockedSet = mutableSetOf<String>()
                snapshots?.documents?.forEach { doc -> newBlockedSet.add(doc.id) }
                if (blockedApps != newBlockedSet) {
                    blockedApps.clear()
                    blockedApps.addAll(newBlockedSet)
                    Log.d(TAG, "Firebase blocked apps updated: ${blockedApps.joinToString()}")
                }
            }
    }

    private fun setupScreenTimeListener() { /* ... Your existing code ... */
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "User not authenticated for screen time monitoring")
            return
        }
        Log.d(TAG, "Setting up Firebase screen time listener for user: $userId")
        screenTimeListener?.remove()
        screenTimeListener = firestore.collection(SCREEN_TIME_COLLECTION)
            .whereEqualTo("user_id", userId) // Ensure this field exists in your ScreenTime docs
            .orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(1)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error listening to Firebase screen time", error)
                    return@addSnapshotListener
                }
                if (snapshots != null && !snapshots.isEmpty) {
                    val doc = snapshots.documents[0]
                    val isCurrentlyBlocked = doc.getBoolean("is_blocked") ?: false
                    val newResetTime = doc.getString("reset_screen_time") ?: ""

                    if (isTimeBasedBlockingEnabled != isCurrentlyBlocked || resetTime != newResetTime) {
                        Log.d(TAG, "Firebase screen time update: Blocked=$isCurrentlyBlocked, Reset=$newResetTime")
                        isTimeBasedBlockingEnabled = isCurrentlyBlocked
                        resetTime = newResetTime
                        if (!isCurrentlyBlocked) isBlockingActivityActive = false // If unblocked via Firebase
                    }
                } else {
                    if (isTimeBasedBlockingEnabled) { // If it was enabled but no doc found, disable it
                        Log.d(TAG, "No screen time doc found, disabling time-based blocking locally.")
                        isTimeBasedBlockingEnabled = false
                        resetTime = ""
                        isBlockingActivityActive = false
                    }
                }
            }
    }

    fun enableTimeBasedBlocking(newResetTime: String) { /* ... Your existing code ... */
        if (!isValidTimeFormat(newResetTime)) {
            Log.e(TAG, "Invalid reset time format: $newResetTime")
            Toast.makeText(this, "Invalid time format (HH:mm).", Toast.LENGTH_SHORT).show()
            return
        }
        isTimeBasedBlockingEnabled = true
        this.resetTime = newResetTime
        Log.d(TAG, "Time-based blocking enabled. Reset at: $resetTime")
        Toast.makeText(this, "Apps will be restricted until $resetTime", Toast.LENGTH_LONG).show()
    }

    fun disableTimeBasedBlocking() { /* ... Your existing code ... */
        isTimeBasedBlockingEnabled = false
        this.resetTime = ""
        isBlockingActivityActive = false // Important reset
        Log.d(TAG, "Time-based blocking disabled.")
        Toast.makeText(this, "App restrictions lifted.", Toast.LENGTH_LONG).show()
    }

    private fun isValidTimeFormat(time: String): Boolean { /* ... Your existing code ... */
        return try {
            SimpleDateFormat("HH:mm", Locale.getDefault()).apply { isLenient = false }.parse(time)
            time.matches(Regex("^([01]?[0-9]|2[0-3]):[0-5][0-9]$"))
        } catch (e: Exception) { false }
    }

    private fun startResetTimeChecker() { /* ... Your existing code ... */
        resetCheckJob?.cancel()
        resetCheckJob = serviceScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL)
                if (isTimeBasedBlockingEnabled && resetTime.isNotEmpty()) {
                    checkAndResetBlockingIfNeeded()
                }
            }
        }
        Log.d(TAG, "Reset time checker job started.")
    }
    private fun checkAndResetBlockingIfNeeded() { /* ... Your existing code, renamed ... */
        try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val resetCal = Calendar.getInstance().apply { time = sdf.parse(resetTime)!! }
            val currentCal = Calendar.getInstance()

            // Set resetCal to today's date
            resetCal.set(Calendar.YEAR, currentCal.get(Calendar.YEAR))
            resetCal.set(Calendar.MONTH, currentCal.get(Calendar.MONTH))
            resetCal.set(Calendar.DAY_OF_MONTH, currentCal.get(Calendar.DAY_OF_MONTH))

            if (currentCal.after(resetCal)) {
                Log.d(TAG, "Reset time ($resetTime) reached. Disabling time-based blocking.")
                disableTimeBasedBlocking()
                // Optionally notify parent app via Firebase that limit reset
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndResetBlockingIfNeeded: ", e)
        }
    }

    private fun registerServiceReceiver() { // Renamed
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction("com.iconbiztechnologies1.childrenapp.ENABLE_BLOCKING")
                addAction("com.iconbiztechnologies1.childrenapp.DISABLE_BLOCKING")
                addAction("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
            }
            // Modern Android requires specifying exported state for receivers
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(broadcastReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(broadcastReceiver, filter) // For older versions, no flag needed or use appropriate flag
            }
            isReceiverRegistered = true
            Log.d(TAG, "Service broadcast receiver registered.")
        }
    }

    private fun unregisterServiceReceiver() { // Renamed
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(broadcastReceiver)
                isReceiverRegistered = false
                Log.d(TAG, "Service broadcast receiver unregistered.")
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Error unregistering receiver (already unregistered?): ", e)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Unified accessibility service interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceInstance?.clear()
        serviceInstance = null
        blockedAppsListener?.remove()
        screenTimeListener?.remove()
        resetCheckJob?.cancel()
        serviceScope.cancel()
        unregisterServiceReceiver()
        Log.d(TAG, "UnifiedAppBlockingAccessibilityService destroyed")
    }
}