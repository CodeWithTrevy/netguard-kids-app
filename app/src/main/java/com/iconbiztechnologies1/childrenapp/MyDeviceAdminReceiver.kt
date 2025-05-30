// MyDeviceAdminReceiver.kt
package com.iconbiztechnologies1.childrenapp // Your package

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    private val TAG = "MyDeviceAdminReceiver"

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "Device Admin: Enabled")
        Toast.makeText(context, "Parental Control Admin Enabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin: Disabled")
        Toast.makeText(context, "Parental Control Admin Disabled", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence? {
        Log.d(TAG, "Device Admin: Disable Requested")
        return "Disabling this app will remove parental controls. Are you sure?"
    }
}