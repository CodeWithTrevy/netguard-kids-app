package com.iconbiztechnologies1.childrenapp

import android.content.ContentValues.TAG
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class ChildName : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private var userId: String? = null // Store userId received from previous activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_child_name)

        db = FirebaseFirestore.getInstance()

        // Get userId from previous activity
        userId = intent.getStringExtra("userId")

        val confirmButton = findViewById<Button>(R.id.buttonConfirm)
        val childNameInput = findViewById<EditText>(R.id.editTextChildName)

        confirmButton.setOnClickListener {
            val childName = childNameInput.text.toString().trim()

            if (userId != null && childName.isNotEmpty()) {
                // Update the existing document with child name in the "Devices" collection
                db.collection("Devices")
                    .document(userId!!) // Update the same document
                    .update("child_name", childName)
                    .addOnSuccessListener {
                        Log.d(TAG, "Child name added successfully!")
                        Toast.makeText(this, "Child saved!", Toast.LENGTH_SHORT).show()

                        // Now, update the user's document in the "Users" collection with the combined device and child name
                        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"

                        // Update fields individually in the "Users" collection
                        db.collection("users")
                            .document(userId!!) // Store under the user's ID
                            .update("device_name", deviceName, "child_name", childName) // Update both fields
                            .addOnSuccessListener {
                                Log.d(TAG, "User data updated successfully!")
                            }
                            .addOnFailureListener { e ->
                                Log.e(TAG, "Error updating user data", e)
                                Toast.makeText(this, "Failed to update user data!", Toast.LENGTH_SHORT).show()
                            }

                        // Navigate to the next activity
                        val intent = Intent(this, InstalledApps::class.java)
                        startActivity(intent)
                        finish()
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "Error saving child name", e)
                        Toast.makeText(this, "Failed to save child!", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(this, "Child name cannot be empty!", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
