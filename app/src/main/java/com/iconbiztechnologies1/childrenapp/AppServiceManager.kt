// AppServiceManager.kt
package com.iconbiztechnologies1.childrenapp

import android.content.Context
import android.content.Intent
import android.util.Log

object AppServiceManager {

    private const val TAG = "AppServiceManager"

    fun startAppMonitoringService(context: Context) {
        try {
            val serviceIntent = Intent(context, AppMonitoringService::class.java)
            context.startService(serviceIntent)
            Log.d(TAG, "App monitoring service started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting app monitoring service", e)
        }
    }

    fun stopAppMonitoringService(context: Context) {
        try {
            val serviceIntent = Intent(context, AppMonitoringService::class.java)
            context.stopService(serviceIntent)
            Log.d(TAG, "App monitoring service stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping app monitoring service", e)
        }
    }
}