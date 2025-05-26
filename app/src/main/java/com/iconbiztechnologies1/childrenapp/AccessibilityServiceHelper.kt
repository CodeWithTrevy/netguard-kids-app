package com.iconbiztechnologies1.childrenapp

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class AccessibilityServiceHelper {

    companion object {
        private const val SERVICE_NAME = "com.iconbiztechnologies1.childrenapp/.AppBlockingAccessibilityService1"

        /**
         * Check if the accessibility service is enabled
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            val enabledServices = accessibilityManager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)

            for (service in enabledServices) {
                if (service.id.contains(SERVICE_NAME)) {
                    return true
                }
            }
            return false
        }

        /**
         * Show dialog to guide user to enable accessibility service
         */
        fun showEnableAccessibilityDialog(activity: AppCompatActivity) {
            AlertDialog.Builder(activity)
                .setTitle("Enable App Blocking")
                .setMessage("""
                    To block apps, NetGuard Kids needs accessibility permission.
                    
                    Steps:
                    1. Tap 'Open Settings' below
                    2. Find 'NetGuard Kids' in the list
                    3. Toggle it ON
                    4. Come back to the app
                    
                    This permission helps us detect when blocked apps are opened.
                """.trimIndent())
                .setPositiveButton("Open Settings") { _, _ ->
                    openAccessibilitySettings(activity)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        /**
         * Open accessibility settings
         */
        private fun openAccessibilitySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to general settings
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            }
        }

        /**
         * Check and prompt for accessibility service if not enabled
         * Call this from your main activity
         */
        fun checkAndPromptForAccessibility(activity: AppCompatActivity) {
            if (!isAccessibilityServiceEnabled(activity)) {
                showEnableAccessibilityDialog(activity)
            }
        }
    }
}