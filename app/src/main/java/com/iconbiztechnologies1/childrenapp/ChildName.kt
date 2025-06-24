package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class ChildName : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private var deviceId: String? = null
    private var userId: String? = null // Kept for consistency, though not used in validation now

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_name)

        db = FirebaseFirestore.getInstance()

        deviceId = intent.getStringExtra(DeviceName.EXTRA_DEVICE_ID)
        userId = intent.getStringExtra(DeviceName.EXTRA_USER_ID)

        if (deviceId == null || userId == null) {
            Log.e(TAG, "Critical Error: Device ID or User ID is null. Cannot proceed.")
            Toast.makeText(this, "An error occurred. Please restart the setup.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        Log.d(TAG, "Received deviceId: $deviceId and userId: $userId")

        val confirmButton = findViewById<Button>(R.id.buttonConfirm)
        val childNameInput = findViewById<EditText>(R.id.editTextChildName)

        confirmButton.setOnClickListener {
            val childName = childNameInput.text.toString().trim()

            if (childName.isEmpty()) {
                childNameInput.error = "Child name cannot be empty"
                Toast.makeText(this, "Please enter the child's name.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Start the simplified validation and update process.
            checkDeviceAndAssignChild(childName)
        }
    }

    /**
     * Simplified validation process.
     * 1. Checks if this specific device is already assigned to any child.
     * 2. If not, it updates the document with the new child's name.
     */
    private fun checkDeviceAndAssignChild(childName: String) {
        // --- CHECK: Is this specific device already assigned to a child? ---
        val deviceDocumentRef = db.collection(DeviceName.DEVICES_COLLECTION).document(deviceId!!)

        deviceDocumentRef.get()
            .addOnSuccessListener { document ->
                // The check is simple: does the document exist and already have a 'child_name' field?
                if (document.exists() && document.contains("child_name")) {
                    // FAILURE: This device is already fully set up. Block the action.
                    Log.w(TAG, "Attempted to re-assign an already configured device ($deviceId).")
                    Toast.makeText(
                        this,
                        "This device is already set up for a child. Please use a different device to add a new child.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    // SUCCESS: This device is "clean". Proceed with the update.
                    Log.d(TAG, "Device $deviceId is unassigned. Proceeding to update.")
                    updateDeviceWithChildName(childName)
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error fetching device document $deviceId", e)
                Toast.makeText(this, "Error validating device. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * --- FINAL STEP: Update the document. This is only called if the check passes. ---
     */
    private fun updateDeviceWithChildName(childName: String) {
        val deviceDocumentRef = db.collection(DeviceName.DEVICES_COLLECTION).document(deviceId!!)

        deviceDocumentRef.update("child_name", childName)
            .addOnSuccessListener {
                Log.d(TAG, "Document $deviceId successfully updated with child name: $childName")
                Toast.makeText(this, "Child's name saved!", Toast.LENGTH_SHORT).show()

                val intent = Intent(this, InstalledApps::class.java)
                intent.putExtra(DeviceName.EXTRA_DEVICE_ID, deviceId)
                startActivity(intent)
                finish()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error updating document $deviceId", e)
                Toast.makeText(this, "Failed to save child's name. Please try again.", Toast.LENGTH_SHORT).show()
            }
    }

    companion object {
        private const val TAG = "ChildNameActivity"
    }
}