// Updated InstalledApps.kt
package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*

class InstalledApps : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var confirmButton: Button
    private lateinit var progressBar: ProgressBar
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val activityScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "InstalledApps"
        private const val APPS_COLLECTION = "Device_installed_app"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_installed_apps)

        initViews()
        startAppMonitoringService()
        loadAppsFromFirebase()
    }

    override fun onDestroy() {
        super.onDestroy()
        activityScope.cancel()
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        confirmButton = findViewById(R.id.button4)
        progressBar = findViewById(R.id.progressBar)

        recyclerView.layoutManager = LinearLayoutManager(this)

        confirmButton.setOnClickListener {
            val intent = Intent(this, AppUsage::class.java)
            startActivity(intent)
        }
    }

    private fun startAppMonitoringService() {
        val serviceIntent = Intent(this, AppMonitoringService::class.java)
        startService(serviceIntent)
    }

    private fun loadAppsFromFirebase() {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            Toast.makeText(this, "User not authenticated", Toast.LENGTH_SHORT).show()
            loadAppsLocally() // Fallback to local loading
            return
        }

        showLoading(true)

        firestore.collection(APPS_COLLECTION)
            .document("child_installed_apps")
            .collection(userId)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.e(TAG, "Error loading apps from Firebase", error)
                    loadAppsLocally() // Fallback to local loading
                    return@addSnapshotListener
                }

                if (snapshots != null && !snapshots.isEmpty) {
                    activityScope.launch {
                        val apps = getApplicationInfoFromFirebase(snapshots.documents.map { it.id })
                        updateRecyclerView(apps)
                        showLoading(false)
                    }
                } else {
                    // No apps in Firebase yet, trigger sync
                    Log.d(TAG, "No apps found in Firebase for user: $userId, syncing...")
                    loadAppsLocally()
                }
            }
    }

    private suspend fun getApplicationInfoFromFirebase(packageNames: List<String>): List<ApplicationInfo> = withContext(Dispatchers.IO) {
        val apps = mutableListOf<ApplicationInfo>()
        val packageManager: PackageManager = packageManager

        packageNames.forEach { packageName ->
            try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                // Double check it's still a user app
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSystemApp = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                val isOwnApp = appInfo.packageName == "com.iconbiztechnologies1.childrenapp"

                if (!isSystemApp && !isUpdatedSystemApp && !isOwnApp) {
                    apps.add(appInfo)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "App no longer installed: $packageName")
                // Optionally remove from Firebase here
                removeAppFromFirebase(packageName)
            }
        }

        apps
    }

    private fun loadAppsLocally() {
        activityScope.launch {
            showLoading(true)
            val apps = getLocalInstalledApps()
            updateRecyclerView(apps)
            showLoading(false)
        }
    }

    private suspend fun getLocalInstalledApps(): List<ApplicationInfo> = withContext(Dispatchers.IO) {
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

    private fun updateRecyclerView(apps: List<ApplicationInfo>) {
        recyclerView.adapter = InstalledAppsAdapter(this, apps)
        Log.d(TAG, "Updated RecyclerView with ${apps.size} apps")
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        recyclerView.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun removeAppFromFirebase(packageName: String) {
        val userId = auth.currentUser?.uid ?: return

        firestore.collection(APPS_COLLECTION)
            .document("child_installed_apps")
            .collection(userId)
            .document(packageName)
            .delete()
            .addOnSuccessListener {
                Log.d(TAG, "Removed uninstalled app from Firebase: $packageName for user: $userId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error removing app from Firebase", e)
            }
    }
}