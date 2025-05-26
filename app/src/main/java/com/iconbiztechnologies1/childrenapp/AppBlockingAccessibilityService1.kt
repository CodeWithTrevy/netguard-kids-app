package com.iconbiztechnologies1.childrenapp

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*

class AppBlockingAccessibilityService1 : AccessibilityService() {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var blockedAppsListener: ListenerRegistration? = null
    private val blockedApps = mutableSetOf<String>()
    private var currentForegroundApp: String? = null

    companion object {
        private const val TAG = "AppBlockingService"
        private const val BLOCKED_APPS_COLLECTION = "blocked_apps"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "AppBlockingAccessibilityService created")
        setupBlockedAppsListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "AppBlockingAccessibilityService destroyed")
        blockedAppsListener?.remove()
        serviceScope.cancel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility service connected")
        setupBlockedAppsListener()
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
        Log.d(TAG, "Accessibility service interrupted")
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString()

        if (packageName != null && packageName != currentForegroundApp) {
            currentForegroundApp = packageName
            Log.d(TAG, "App in foreground: $packageName")

            // Check if the app is blocked
            if (blockedApps.contains(packageName)) {
                Log.d(TAG, "Blocked app detected: $packageName")
                blockApp(packageName)
            }
        }
    }

    private fun blockApp(packageName: String) {
        try {
            val appName = getAppName(packageName)

            // Launch the blocking activity
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
            Log.d(TAG, "Launched blocking activity for: $packageName")

            // Instead of trying to kill the app (which requires special permissions and doesn't work well),
            // we rely on the blocking activity to stay on top and redirect to home
            // The blocking activity handles preventing return to the blocked app

        } catch (e: Exception) {
            Log.e(TAG, "Error blocking app: $packageName", e)
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

    private fun setupBlockedAppsListener() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.e(TAG, "User not authenticated")
            return
        }

        Log.d(TAG, "Setting up blocked apps listener for user: $userId")

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

                    Log.d(TAG, "Updated blocked apps list: ${blockedApps.size} apps blocked")
                    Log.d(TAG, "Blocked apps: ${blockedApps.joinToString(", ")}")

                    // Log changes for debugging
                    if (blockedApps.size != previousSize) {
                        Log.d(TAG, "Blocked apps count changed from $previousSize to ${blockedApps.size}")
                    }
                }
            }
    }
}