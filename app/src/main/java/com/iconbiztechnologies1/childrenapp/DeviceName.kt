package com.iconbiztechnologies1.childrenapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
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
    private lateinit var deviceName: String // Store device name globally

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_name)

        // Initialize Firebase
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // UI Elements
        val deviceNameEditText = findViewById<EditText>(R.id.deviceNameEditText)
        val nextButton = findViewById<Button>(R.id.button2)

        // Retrieve device name
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        deviceNameEditText.setText(deviceName)

        nextButton.setOnClickListener {
            val user = auth.currentUser
            if (user != null) {
                val userId = user.uid // Get logged-in user ID

                // Save device name to Firestore under user's ID (without child name yet)
                val deviceData = hashMapOf(
                    "device_name" to deviceName,
                    "user_id" to userId,
                    "timestamp" to System.currentTimeMillis()
                )

                db.collection("Devices") // Store devices under this collection
                    .document(userId) // Each user has only one device entry
                    .set(deviceData) // Create or update document
                    .addOnSuccessListener {
                        Log.d(TAG, "Device name stored successfully!")

                        // Navigate to ChildName activity with userId
                        val intent = Intent(this, ChildName::class.java)
                        intent.putExtra("userId", userId) // Pass userId to next activity
                        startActivity(intent)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error saving device name", e)
                        Toast.makeText(this, "Failed to save device!", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "User not logged in!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val TAG = "DeviceNameActivity"
    }
}
