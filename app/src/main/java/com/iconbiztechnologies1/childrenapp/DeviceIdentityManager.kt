// File: DeviceIdentityManager.kt
package com.iconbiztechnologies1.childrenapp

import android.content.Context
import android.content.SharedPreferences

/**
 * A singleton helper object to manage storing and retrieving the unique
 * physical device ID from SharedPreferences. This allows different
 * components of the app (like background services) to access the ID
 * after it has been established during setup.
 */
object DeviceIdentityManager {

    private const val PREFS_NAME = "DevicePrefs"
    private const val KEY_PHYSICAL_DEVICE_ID = "physical_device_id"

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Saves the unique physical device ID to local storage.
     * This should be called from DeviceName.kt after a successful registration.
     */
    fun saveDeviceID(context: Context, deviceId: String) {
        val editor = getPreferences(context).edit()
        editor.putString(KEY_PHYSICAL_DEVICE_ID, deviceId)
        editor.apply()
    }

    /**
     * Retrieves the unique physical device ID from local storage.
     * The monitoring services will use this to identify the device.
     * Returns null if the ID has not been saved yet.
     */
    fun getDeviceID(context: Context): String? {
        return getPreferences(context).getString(KEY_PHYSICAL_DEVICE_ID, null)
    }
}