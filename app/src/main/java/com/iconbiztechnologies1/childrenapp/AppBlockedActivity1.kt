package com.iconbiztechnologies1.childrenapp

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

class AppBlockedActivity1 : AppCompatActivity() {

    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName

    // --- Views ---
    private lateinit var blockedAppIcon: ImageView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var goHomeButton: Button

    private var blockedPackage: String? = null

    companion object {
        private const val TAG = "AppBlockedActivity1"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_blocked1) // Ensure you have this layout file

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        initViews()

        // Get blocked app info from intent
        val intent = intent
        blockedPackage = intent.getStringExtra("blocked_package")
        val blockedAppName = intent.getStringExtra("blocked_app_name") ?: "This App"
        val blockType = intent.getStringExtra("block_type")
        val contentDescriptor = intent.getStringExtra("content_descriptor")

        Log.d(TAG, "Device Admin Blocker started for: $blockedAppName ($blockedPackage)")

        // --- CORE LOGIC: Immediately disable the app ---
        if (!blockedPackage.isNullOrEmpty() && dpm.isAdminActive(adminComponent)) {
            try {
                // This is the Device Admin call to disable the app
                dpm.setApplicationHidden(adminComponent, blockedPackage, true)
                Log.i(TAG, "SUCCESS: Application '$blockedPackage' has been disabled by Device Admin.")
            } catch (e: SecurityException) {
                Log.e(TAG, "FAILED to disable app. Is Device Admin active and has policy?", e)
            }
        }

        setupUI(blockedAppName, blockType, contentDescriptor)

        goHomeButton.setOnClickListener {
            // Notify the service that this blocking action is complete
            notifyServiceAndGoHome()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Prevent going back, force user to the home screen
        notifyServiceAndGoHome()
        super.onBackPressed()
    }

    private fun initViews() {
        blockedAppIcon = findViewById(R.id.ivBlockedAppIcon)
        titleText = findViewById(R.id.tvBlockedAppName) // Assuming this is your main title
        detailText = findViewById(R.id.tvBlockingMessage) // Assuming this is for the details
        goHomeButton = findViewById(R.id.btnGoHome)
    }

    private fun setupUI(appName: String, blockType: String?, content: String?) {
        titleText.text = "$appName Blocked"

        // Customize the message based on the block reason
        detailText.text = when (blockType) {
            UnifiedAppBlockingAccessibilityService.REASON_ADMIN ->
                "Access to this app has been restricted by your parent or guardian."
            UnifiedAppBlockingAccessibilityService.REASON_URL ->
                "Access to this browser was blocked for visiting a restricted site:\n\n$content"
            UnifiedAppBlockingAccessibilityService.REASON_YOUTUBE ->
                "YouTube was blocked due to restricted content being displayed:\n\n\"$content\""
            else ->
                "This application has been blocked by NetGuard Kids."
        }

        // Try to load app icon
        try {
            val appIcon = packageManager.getApplicationIcon(blockedPackage!!)
            blockedAppIcon.setImageDrawable(appIcon)
        } catch (e: PackageManager.NameNotFoundException) {
            blockedAppIcon.setImageResource(R.drawable.ic_block) // Use a generic block icon
        }
    }

    private fun notifyServiceAndGoHome() {
        // Notify the service that the blocking overlay can be dismissed,
        // allowing it to block another app if needed.
        val intent = Intent("com.iconbiztechnologies1.childrenapp.BLOCKING_ACTIVITY_FINISHED")
        intent.setPackage(packageName)
        sendBroadcast(intent)

        // Go to home screen
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(homeIntent)
        finish() // Close this blocking activity
    }
}