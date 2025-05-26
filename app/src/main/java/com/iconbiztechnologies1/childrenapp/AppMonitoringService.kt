// AppMonitoringService.kt
package com.iconbiztechnologies1.childrenapp

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

class AppMonitoringService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var packageReceiver: BroadcastReceiver
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "AppMonitoringService"
        private const val APPS_COLLECTION = "Device_installed_app"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        setupPackageReceiver()
        // Initial sync of all apps
        syncInstalledApps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY // Restart service if killed
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered")
        }
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }

    private fun setupPackageReceiver() {
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_PACKAGE_ADDED -> {
                        val packageName = intent.data?.schemeSpecificPart
                        Log.d(TAG, "Package added: $packageName")
                        packageName?.let { handleAppInstalled(it) }
                    }
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        val packageName = intent.data?.schemeSpecificPart
                        Log.d(TAG, "Package removed: $packageName")
                        packageName?.let { handleAppUninstalled(it) }
                    }
                    Intent.ACTION_PACKAGE_REPLACED -> {
                        val packageName = intent.data?.schemeSpecificPart
                        Log.d(TAG, "Package replaced: $packageName")
                        packageName?.let { handleAppUpdated(it) }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        registerReceiver(packageReceiver, filter)
    }

    private fun syncInstalledApps() {
        serviceScope.launch {
            try {
                val installedApps = getInstalledApps()
                uploadAppsToFirebase(installedApps)
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing apps", e)
            }
        }
    }

    private fun handleAppInstalled(packageName: String) {
        serviceScope.launch {
            try {
                val appInfo = getAppInfo(packageName)
                appInfo?.let { uploadSingleAppToFirebase(it, "installed") }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling app installation", e)
            }
        }
    }

    private fun handleAppUninstalled(packageName: String) {
        serviceScope.launch {
            try {
                removeAppFromFirebase(packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling app uninstallation", e)
            }
        }
    }

    private fun handleAppUpdated(packageName: String) {
        serviceScope.launch {
            try {
                val appInfo = getAppInfo(packageName)
                appInfo?.let { uploadSingleAppToFirebase(it, "updated") }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling app update", e)
            }
        }
    }

    private suspend fun getInstalledApps(): List<ApplicationInfo> = withContext(Dispatchers.IO) {
        val packageManager: PackageManager = packageManager
        val allApps = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
            } else {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            }
        } catch (e: Exception) {
            val launchIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            packageManager.queryIntentActivities(launchIntent, 0).mapNotNull { resolveInfo ->
                try {
                    packageManager.getApplicationInfo(resolveInfo.activityInfo.packageName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }
        }

        // Filter out system apps and own app
        allApps.filter { appInfo ->
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isOwnApp = appInfo.packageName == "com.iconbiztechnologies1.childrenapp"

            !isSystemApp && !isUpdatedSystemApp && !isOwnApp
        }
    }

    private suspend fun getAppInfo(packageName: String): ApplicationInfo? = withContext(Dispatchers.IO) {
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            // Check if it's a user app (not system app)
            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val isOwnApp = appInfo.packageName == "com.iconbiztechnologies1.childrenapp"

            if (!isSystemApp && !isUpdatedSystemApp && !isOwnApp) {
                appInfo
            } else {
                null
            }
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "Package not found: $packageName")
            null
        }
    }

    private suspend fun uploadAppsToFirebase(apps: List<ApplicationInfo>) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "User not authenticated")
            return@withContext
        }

        val batch = firestore.batch()
        val userAppsCollection = firestore.collection(APPS_COLLECTION)
            .document("child_installed_apps")
            .collection(userId)

        apps.forEach { appInfo ->
            val appData = createAppDataMap(appInfo, "synced")
            val docRef = userAppsCollection.document(appInfo.packageName)
            batch.set(docRef, appData)
        }

        try {
            batch.commit().await()
            Log.d(TAG, "Successfully uploaded ${apps.size} apps to Firebase for user: $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading apps to Firebase", e)
        }
    }

    private suspend fun uploadSingleAppToFirebase(appInfo: ApplicationInfo, action: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "User not authenticated")
            return@withContext
        }

        val appData = createAppDataMap(appInfo, action)
        val docRef = firestore.collection(APPS_COLLECTION)
            .document("child_installed_apps")
            .collection(userId)
            .document(appInfo.packageName)

        try {
            docRef.set(appData).await()
            Log.d(TAG, "Successfully uploaded app: ${appInfo.packageName} for user: $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading app to Firebase", e)
        }
    }

    private suspend fun removeAppFromFirebase(packageName: String) = withContext(Dispatchers.IO) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Log.w(TAG, "User not authenticated")
            return@withContext
        }

        val docRef = firestore.collection(APPS_COLLECTION)
            .document("child_installed_apps")
            .collection(userId)
            .document(packageName)

        try {
            docRef.delete().await()
            Log.d(TAG, "Successfully removed app: $packageName for user: $userId")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing app from Firebase", e)
        }
    }

    private fun createAppDataMap(appInfo: ApplicationInfo, action: String): Map<String, Any> {
        val appName = try {
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            appInfo.packageName
        }

        // Get device information
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val deviceName = Build.DEVICE
        val androidVersion = Build.VERSION.RELEASE

        return mapOf(
            "packageName" to appInfo.packageName,
            "appName" to appName,
            "lastAction" to action,
            "timestamp" to System.currentTimeMillis(),
            "dateAdded" to Date(),
            "flags" to appInfo.flags,
            "enabled" to appInfo.enabled,
            "userId" to (auth.currentUser?.uid ?: "unknown"),
            "deviceId" to deviceId,
            "deviceModel" to deviceModel,
            "deviceName" to deviceName,
            "androidVersion" to androidVersion,
            "deviceInfo" to mapOf(
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "brand" to Build.BRAND,
                "product" to Build.PRODUCT,
                "hardware" to Build.HARDWARE,
                "sdkVersion" to Build.VERSION.SDK_INT,
                "androidVersion" to Build.VERSION.RELEASE
            )
        )
    }
}