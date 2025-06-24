// File: DeviceName.kt
package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class DeviceName : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_name)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        val deviceNameEditText = findViewById<EditText>(R.id.deviceNameEditText)
        val nextButton = findViewById<Button>(R.id.button2)

        val defaultDeviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        deviceNameEditText.setText(defaultDeviceName)

        nextButton.setOnClickListener {
            val user = auth.currentUser
            if (user == null) {
                Toast.makeText(this, "User not logged in!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Get the user-entered device name
            val finalDeviceName = deviceNameEditText.text.toString().trim()
            if (finalDeviceName.isEmpty()) {
                deviceNameEditText.error = "Device name cannot be empty"
                return@setOnClickListener
            }

            // Start the entire registration process, which now begins with checking the physical device.
            startRegistrationProcess(user.uid, finalDeviceName)
        }
    }

    /**
     * Orchestrates the entire setup flow, starting with checking if this physical device
     * is already registered and fully set up.
     */
    private fun startRegistrationProcess(userId: String, deviceName: String) {
        // 1. Get the unique physical ID for this device installation.
        val physicalDeviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        Log.d(TAG, "This physical device's ID is: $physicalDeviceId")

        // 2. Check if this physical device is already registered for this user and fully set up.
        db.collection(DEVICES_COLLECTION)
            .whereEqualTo("user_id", userId)
            .whereEqualTo("physical_device_id", physicalDeviceId)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (!documents.isEmpty) {
                    // A document for this physical device and user already exists.
                    val existingDocument = documents.documents[0]
                    val deviceId = existingDocument.id
                    val childName = existingDocument.getString("child_name")

                    if (childName != null && childName.isNotEmpty()) {
                        // IT IS FULLY SET UP! Navigate directly to the main app activity.
                        Log.d(TAG, "Device is already fully registered to '$childName'. Navigating to main activity.")
                        Toast.makeText(this, "Welcome back! This device is assigned to $childName.", Toast.LENGTH_LONG).show()

                        // Save the ID here to handle app reinstalls where the
                        // device record already exists in Firestore but local storage is empty.
                        DeviceIdentityManager.saveDeviceID(this, physicalDeviceId)
                        Log.d(TAG, "Saved physical_device_id ($physicalDeviceId) on reinstall.")


                        val intent = Intent(this, AppUsage::class.java)
                        intent.putExtra(EXTRA_DEVICE_ID, deviceId) // Pass the deviceId so the activity knows what to show
                        startActivity(intent)
                        finishAffinity() // Finish this and all parent activities
                        return@addOnSuccessListener
                    }
                }

                // If we reach here, it means either:
                // a) This is a brand new device registration.
                // b) The previous registration was incomplete (no child_name).
                // In both cases, we should proceed with the normal setup flow.
                Log.d(TAG, "This is a new or incomplete registration. Proceeding with setup.")
                checkIfDeviceNameIsUnique(userId, deviceName, physicalDeviceId)

            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking for existing physical device", e)
                Toast.makeText(this, "Error checking device. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }


    /**
     * Checks if the user-provided device name is already in use for this parent.
     * This is the second step in the validation process.
     */
    private fun checkIfDeviceNameIsUnique(userId: String, deviceName: String, physicalDeviceId: String) {
        db.collection(DEVICES_COLLECTION)
            .whereEqualTo("user_id", userId)
            .whereEqualTo("device_name", deviceName)
            .limit(1)
            .get()
            .addOnSuccessListener { documents ->
                if (documents.isEmpty) {
                    createDeviceDocument(userId, deviceName, physicalDeviceId)
                } else {
                    Toast.makeText(this, "This device name is already in use. Please choose another.", Toast.LENGTH_LONG).show()
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error checking for unique device name", e)
                Toast.makeText(this, "Error checking device. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Creates the new device document, now including the physical device ID.
     */
    private fun createDeviceDocument(userId: String, deviceName: String, physicalDeviceId: String) {
        val deviceData = hashMapOf(
            "device_name" to deviceName,
            "user_id" to userId,
            "physical_device_id" to physicalDeviceId, // <-- Storing the physical ID
            "timestamp" to System.currentTimeMillis()
        )

        db.collection(DEVICES_COLLECTION)
            .add(deviceData)
            .addOnSuccessListener { documentReference ->
                val newDeviceId = documentReference.id
                Log.d(TAG, "New device document created with ID: $newDeviceId")

                // Save the physical device ID to SharedPreferences upon successful creation.
                // This makes it available to the background services.
                DeviceIdentityManager.saveDeviceID(this, physicalDeviceId)
                Log.d(TAG, "Saved physical_device_id ($physicalDeviceId) to local storage.")

                val intent = Intent(this, ChildName::class.java)
                intent.putExtra(EXTRA_DEVICE_ID, newDeviceId)
                intent.putExtra(EXTRA_USER_ID, userId)
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error saving new device data", e)
                Toast.makeText(this, "Failed to save device! Please try again.", Toast.LENGTH_LONG).show()
            }
    }

    companion object {
        private const val TAG = "DeviceNameActivity"
        const val DEVICES_COLLECTION = "Devices"
        const val EXTRA_DEVICE_ID = "com.iconbiztechnologies1.childrenapp.DEVICE_ID"
        const val EXTRA_USER_ID = "com.iconbiztechnologies1.childrenapp.USER_ID"
    }
}