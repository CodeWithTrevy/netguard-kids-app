package com.iconbiztechnologies1.childrenapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
// If you use ViewCompat and WindowInsetsCompat, keep these imports
// import androidx.core.view.ViewCompat
// import androidx.core.view.WindowInsetsCompat

class AdminSettings : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName
    private lateinit var enableAdminLauncher: ActivityResultLauncher<Intent>

    private lateinit var activateButton: Button // Using your button ID

    companion object {
        private const val TAG = "AdminSettings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // You had this
        setContentView(R.layout.activity_admin_settings) // Your layout

        // Initialize Device Admin components
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        // Ensure MyDeviceAdminReceiver is the correct name and in your package
        adminComponentName = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // Register for activity result for Device Admin activation
        enableAdminLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "Device Admin Enabled by user")
                Toast.makeText(this, "Device Admin Enabled Successfully!", Toast.LENGTH_SHORT).show()
                updateButtonState() // Update button text/state
                navigateToNextScreen() // Proceed to the next step/activity
            } else {
                Log.d(TAG, "Device Admin Enabling Cancelled or Failed")
                Toast.makeText(this, "Device Admin activation is required. Please enable it to continue.", Toast.LENGTH_LONG).show()
                updateButtonState() // Reflect that admin is still not active
            }
        }

        activateButton = findViewById<Button>(R.id.button7) // Your button ID from XML
        activateButton.setOnClickListener {
            handleActivationClick()
        }

        // Initial button state update
        updateButtonState()
    }

    override fun onResume() {
        super.onResume()
        // Update button state in case the user manually changed admin status
        // from settings and came back to the app, or if activation was pending.
        updateButtonState()
    }

    private fun isAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponentName)
    }

    private fun updateButtonState() {
        if (isAdminActive()) {
            activateButton.text = "Admin Active (Continue)" // Or just "Continue"
            // You might want to change behavior or just text
        } else {
            activateButton.text = "Activate Now"
        }
    }

    private fun handleActivationClick() {
        if (!isAdminActive()) {
            requestAdminPrivileges()
        } else {
            // Admin is already active, so we can proceed
            Toast.makeText(this, "Device Admin is already active. Proceeding...", Toast.LENGTH_SHORT).show()
            navigateToNextScreen()
        }
    }

    private fun requestAdminPrivileges() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponentName)
        // This explanation is shown on the system screen when asking for permission.
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "To protect your child, this app needs Device Administrator permission. This helps prevent easy uninstallation and ensures parental controls remain active."
        )
        enableAdminLauncher.launch(intent)
    }

    private fun navigateToNextScreen() {
        // Navigate to DailyUsageActivity or your next setup screen
        val intent = Intent(this, DailyUsageActivity::class.java)
        startActivity(intent)
        // Optional: finish this AdminSettings activity so the user can't easily go back
        // to this specific setup step if it's part of an onboarding flow.
        finish()
    }
}