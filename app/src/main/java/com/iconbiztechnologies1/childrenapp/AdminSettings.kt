// File: AdminSettings.kt
package com.iconbiztechnologies1.childrenapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class AdminSettings : AppCompatActivity() {

    private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var adminComponentName: ComponentName
    private lateinit var enableAdminLauncher: ActivityResultLauncher<Intent>
    private lateinit var activateButton: Button

    companion object {
        private const val TAG = "AdminSettings"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin_settings)

        // Initialize Device Admin components
        devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponentName = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // Register for activity result
        enableAdminLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Log.d(TAG, "Device Admin Enabled by user")
                Toast.makeText(this, "Device Admin Enabled Successfully! Final step...", Toast.LENGTH_SHORT).show()
                updateButtonState()
                navigateToNextScreen()
            } else {
                Log.d(TAG, "Device Admin Enabling Cancelled or Failed")
                Toast.makeText(this, "Device Admin activation is required to continue.", Toast.LENGTH_LONG).show()
                updateButtonState()
            }
        }

        activateButton = findViewById(R.id.button7)
        activateButton.setOnClickListener {
            handleActivationClick()
        }

        updateButtonState()
    }

    override fun onResume() {
        super.onResume()
        updateButtonState()
    }

    private fun isAdminActive(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponentName)
    }

    private fun updateButtonState() {
        if (isAdminActive()) {
            activateButton.text = "Setup Complete (Continue)"
        } else {
            activateButton.text = "Activate Final Protection"
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
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "To protect your child, this app needs Device Administrator permission. This helps prevent easy uninstallation and ensures parental controls remain active."
        )
        enableAdminLauncher.launch(intent)
    }

    private fun navigateToNextScreen() {
        // SOLUTION: We launch DailyUsageActivity with NO extras.
        // It will now know to get the device ID from the local DeviceIdentityManager.
        val intent = Intent(this, DailyUsageActivity::class.java)
        startActivity(intent)
        finish() // Finish this setup activity
    }
}