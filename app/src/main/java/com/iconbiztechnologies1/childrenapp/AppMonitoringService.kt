// File: AppMonitoringService.kt
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
        // New top-level collection name for this data.
        private const val APPS_COLLECTION = "DeviceInstalledApps"
    }

    override fun onCreate() {
        super.onCreate()
        setupPackageReceiver()
        syncInstalledApps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun uploadAppsToFirebase(apps: List<ApplicationInfo>) = withContext(Dispatchers.IO) {
        val physicalDeviceId = DeviceIdentityManager.getDeviceID(this@AppMonitoringService) ?: return@withContext
        val parentUserId = auth.currentUser?.uid ?: "unknown"

        val batch = firestore.batch()
        // The path now uses the physical device ID as the document ID.
        val deviceAppsCollection = firestore.collection(APPS_COLLECTION).document(physicalDeviceId).collection("apps")

        apps.forEach { appInfo ->
            val appData = createAppInfoMap(appInfo, "synced", parentUserId)
            val docRef = deviceAppsCollection.document(appInfo.packageName)
            batch.set(docRef, appData)
        }
        try {
            batch.commit().await()
            Log.d(TAG, "Successfully synced ${apps.size} apps to Firebase for device: $physicalDeviceId")
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing apps to Firebase", e)
        }
    }

    private suspend fun uploadSingleAppToFirebase(appInfo: ApplicationInfo, action: String) = withContext(Dispatchers.IO) {
        val physicalDeviceId = DeviceIdentityManager.getDeviceID(this@AppMonitoringService) ?: return@withContext
        val parentUserId = auth.currentUser?.uid ?: "unknown"
        val appData = createAppInfoMap(appInfo, action, parentUserId)
        val docRef = firestore.collection(APPS_COLLECTION).document(physicalDeviceId).collection("apps").document(appInfo.packageName)
        try {
            docRef.set(appData).await()
        } catch (e: Exception) {
            Log.e(TAG, "Error uploading app to Firebase", e)
        }
    }

    private suspend fun removeAppFromFirebase(packageName: String) = withContext(Dispatchers.IO) {
        val physicalDeviceId = DeviceIdentityManager.getDeviceID(this@AppMonitoringService) ?: return@withContext
        val docRef = firestore.collection(APPS_COLLECTION).document(physicalDeviceId).collection("apps").document(packageName)
        try {
            docRef.delete().await()
        } catch (e: Exception) {
            Log.e(TAG, "Error removing app from Firebase", e)
        }
    }

    private fun createAppInfoMap(appInfo: ApplicationInfo, action: String, parentUserId: String): Map<String, Any> {
        return mapOf(
            "packageName" to appInfo.packageName,
            "appName" to packageManager.getApplicationLabel(appInfo).toString(),
            "lastAction" to action,
            "timestamp" to System.currentTimeMillis(),
            "enabled" to appInfo.enabled,
            "parentUserId" to parentUserId, // Storing parent's ID is useful for cross-referencing
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "androidVersion" to Build.VERSION.RELEASE
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(packageReceiver)
        serviceScope.cancel()
    }

    private fun setupPackageReceiver() {
        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val packageName = intent?.data?.schemeSpecificPart ?: return
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> handleAppInstalled(packageName)
                    Intent.ACTION_PACKAGE_REMOVED -> handleAppUninstalled(packageName)
                    Intent.ACTION_PACKAGE_REPLACED -> handleAppUpdated(packageName)
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

    private fun syncInstalledApps() = serviceScope.launch {
        getInstalledApps()?.let { uploadAppsToFirebase(it) }
    }

    private fun handleAppInstalled(packageName: String) = serviceScope.launch {
        getAppInfo(packageName)?.let { uploadSingleAppToFirebase(it, "installed") }
    }

    private fun handleAppUninstalled(packageName: String) = serviceScope.launch {
        removeAppFromFirebase(packageName)
    }

    private fun handleAppUpdated(packageName: String) = serviceScope.launch {
        getAppInfo(packageName)?.let { uploadSingleAppToFirebase(it, "updated") }
    }

    private suspend fun getInstalledApps(): List<ApplicationInfo>? = withContext(Dispatchers.IO) {
        try {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != packageName }
        } catch (e: Exception) { null }
    }

    private suspend fun getAppInfo(packageName: String): ApplicationInfo? = withContext(Dispatchers.IO) {
        try {
            packageManager.getApplicationInfo(packageName, 0)
                .takeIf { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != this@AppMonitoringService.packageName }
        } catch (e: PackageManager.NameNotFoundException) { null }
    }
}