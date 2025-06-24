// File: AppUsage.kt
package com.iconbiztechnologies1.childrenapp

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

class AppUsage : AppCompatActivity() {

    private val TAG = "AppUsage"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_app_usauge)

        // Set up description text
        val descriptionText = findViewById<TextView>(R.id.usageAccessDescription)
        descriptionText?.text = "To monitor which apps are being used and for how long, we need " +
                "permission to access usage statistics. This helps parents keep track of screen time."

        val allowButton = findViewById<Button>(R.id.button6)
        updateButtonText(allowButton)

        allowButton.setOnClickListener {
            if (hasUsageStatsPermission()) {
                // Start the service only if permission is granted
                startAppUsageService()
            } else {
                // Request permission first
                Toast.makeText(this, "Please grant usage access permission first", Toast.LENGTH_LONG).show()
                requestUsageAccessPermission()
            }
        }
    }

    private fun updateButtonText(button: Button) {
        if (hasUsageStatsPermission()) {
            button.text = "Start Monitoring"
        } else {
            button.text = "Allow Usage Access"
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val usageStatsManager = getSystemService(
            Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        // Check if we can get any stats, which indicates permission is granted
        val stats = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY, time - 1000*60*60*24, time
        )
        return stats != null && stats.isNotEmpty()
    }

    private fun requestUsageAccessPermission() {
        try {
            Log.d(TAG, "Requesting usage access permission")
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening usage access settings: ${e.message}")
            Toast.makeText(this, "Unable to open usage access settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAppUsageService() {
        try {
            Log.d(TAG, "Starting AppUsageService")
            val serviceIntent = Intent(this, AppUsageService::class.java)

            // Start as foreground service for Android O+
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "App monitoring service started!", Toast.LENGTH_SHORT).show()

            // Navigate to next screen
            val intent = Intent(this, AdminSettings::class.java)
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service: ${e.message}")
            Toast.makeText(this, "Failed to start monitoring service", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // Check if permission was granted when returning from settings
        val allowButton = findViewById<Button>(R.id.button6)
        updateButtonText(allowButton)
    }
}